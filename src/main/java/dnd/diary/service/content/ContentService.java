package dnd.diary.service.content;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import dnd.diary.config.RedisDao;
import dnd.diary.domain.content.Content;
import dnd.diary.domain.content.ContentImage;
import dnd.diary.domain.content.Emotion;
import dnd.diary.domain.group.Group;
import dnd.diary.domain.user.User;
import dnd.diary.dto.content.ContentDto;
import dnd.diary.enumeration.Result;
import dnd.diary.exception.CustomException;
import dnd.diary.repository.content.CommentRepository;
import dnd.diary.repository.content.ContentImageRepository;
import dnd.diary.repository.content.ContentRepository;
import dnd.diary.repository.content.EmotionRepository;
import dnd.diary.repository.group.GroupRepository;
import dnd.diary.repository.user.UserRepository;
import dnd.diary.response.CustomResponseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContentService {
    private final RedisDao redisDao;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final ContentImageRepository contentImageRepository;
    private final EmotionRepository emotionRepository;
    private final AmazonS3Client amazonS3Client;
    @Value("${cloud.aws.s3.bucket}")
    private String bucket;

    @Transactional
    public CustomResponseEntity<Page<ContentDto.groupListPagePostsDto>> groupListContent(
            UserDetails userDetails, Long groupId, Integer page
    ) {
        validateGroupListContent(groupId);

        Page<Content> contents = contentRepository.findByGroupId(
                groupId, PageRequest.of(page - 1, 10, Sort.Direction.DESC, "createdAt")
        );
        return CustomResponseEntity.success(
                getGroupListPagePostsDtos(userDetails, contents)
        );
    }

    @Transactional
    public CustomResponseEntity<Page<ContentDto.groupListPagePostsDto>> groupAllListContent(
            UserDetails userDetails, List<Long> groupId, Integer page
    ) {
        validateGroupAllListContent(groupId);

        Page<Content> contents = contentRepository.findByGroupIdIn(
                groupId, PageRequest.of(page - 1, 10, Sort.Direction.DESC, "createdAt")
        );
        return CustomResponseEntity.success(
                getGroupListPagePostsDtos(userDetails, contents)
        );
    }

    @Transactional
    public CustomResponseEntity<ContentDto.CreateDto> createContent(
            UserDetails userDetails, Long groupId, List<MultipartFile> multipartFile, ContentDto.CreateDto request
    ) {
        Group group = getGroup(groupId);
        Content content = contentRepository.save(
                Content.builder()
                        .content(request.getContent())
                        .latitude(request.getLatitude())
                        .longitude(request.getLongitude())
                        .views(0L)
                        .contentLink("test")
                        .user(getUser(userDetails))
                        .group(group)
                        .build()
        );
        String redisKey = content.getId().toString();

        group.updateRecentModifiedAt(LocalDateTime.now());
        redisDao.setValues(redisKey,"0");

        if (multipartFile == null) {
            return CustomResponseEntity.success(
                    ContentDto.CreateDto.response(
                            content,
                            null
                    )
            );
        } else {
            uploadFiles(multipartFile, content);
            return CustomResponseEntity.success(
                    ContentDto.CreateDto.response(
                            content,
                            contentImageRepository.findByContentId(content.getId())
                                    .stream()
                                    .map(ContentDto.ImageResponseDto::response)
                                    .toList()
                    )
            );
        }
    }

    @Transactional
    public CustomResponseEntity<ContentDto.detailDto> detailContent(UserDetails userDetails, Long contentId) {
        Content content = getContent(contentId);
        String redisKey = contentId.toString();
        String values = redisDao.getValues(redisKey);

        int views = Integer.parseInt(values) + 1;
        redisDao.setValues(redisKey,String.valueOf(views));

        return CustomResponseEntity.success(
                ContentDto.detailDto.response(
                        content,
                        views,
                        content.getContentImages()
                                .stream()
                                .map(ContentDto.ImageResponseDto::response)
                                .toList()
                )
        );
    }

    @Transactional
    public CustomResponseEntity<ContentDto.UpdateDto> updateContent(
            UserDetails userDetails, Long contentId, List<MultipartFile> multipartFile, ContentDto.UpdateDto request
    ) {
        validateUpdateContent(contentId);

        Content content = existsContentAndUser(contentId,getUser(userDetails).getId());
        deleteContentImage(multipartFile, request, content);
        String redisKey = content.getId().toString();

        return CustomResponseEntity.success(
                ContentDto.UpdateDto.response(
                        contentRepository.save(
                                Content.builder()
                                        .id(content.getId())
                                        .content(request.getContent())
                                        .latitude(request.getLatitude())
                                        .longitude(request.getLongitude())
                                        .views(content.getViews())
                                        .contentLink(content.getContentLink())
                                        .user(content.getUser())
                                        .group(content.getGroup())
                                        .build()
                        ),
                        contentImageRepository.findByContentId(content.getId())
                                .stream()
                                .map(ContentDto.ImageResponseDto::response)
                                .toList(),
                        Integer.parseInt(redisDao.getValues(redisKey))
                )
        );
    }

    @Transactional
    public CustomResponseEntity<ContentDto.deleteContent> deleteContent(
            UserDetails userDetails, Long contentId
    ) {
        contentRepository.delete(
                existsContentAndUser(contentId, getUser(userDetails).getId())
        );
        return CustomResponseEntity.successDeleteContent();
    }

    // method

    private void uploadFiles(List<MultipartFile> multipartFile, Content content) {
        multipartFile.forEach(file -> {
            String fileName = saveImage(file);
            ContentImage contentImage = ContentImage.builder()
                    .content(content)
                    .imageName(fileName)
                    .imageUrl(amazonS3Client.getUrl(bucket, fileName).toString())
                    .build();
            contentImageRepository.save(contentImage);
        });
    }

    private Content existsContentAndUser(Long contentId, Long userId) {
        return contentRepository.findByIdAndUserId(contentId, userId)
                .orElseThrow(
                        () -> new CustomException(Result.NOT_MATCHED_USER_CONTENT)
                );
    }

    private String saveImage(MultipartFile file) {
        String fileName = createFileName(file.getOriginalFilename());
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(file.getSize());
        objectMetadata.setContentType(file.getContentType());

        try (InputStream inputStream = file.getInputStream()) {
            amazonS3Client.putObject(new PutObjectRequest(bucket, fileName, inputStream, objectMetadata)
                    .withCannedAcl(CannedAccessControlList.PublicRead));

        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다.");
        }
        return fileName;
    }

    public void deleteFile(String fileName) {
        amazonS3Client.deleteObject(new DeleteObjectRequest(bucket, fileName));
    }

    private String createFileName(String fileName) {
        return UUID.randomUUID().toString().concat(getFileExtension(fileName));
    }

    private String getFileExtension(String fileName) {
        try {
            return fileName.substring(fileName.lastIndexOf("."));
        } catch (StringIndexOutOfBoundsException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "잘못된 형식의 파일(" + fileName + ") 입니다.");
        }
    }

    private Group getGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(
                        () -> new CustomException(Result.NOT_FOUND_GROUP)
                );
    }

    private User getUser(UserDetails userDetails) {
        User user = userRepository.findOneWithAuthoritiesByEmail(userDetails.getUsername())
                .orElseThrow(
                        () -> new CustomException(Result.FAIL)
                );
        return user;
    }

    private Content getContent(Long contentId) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(
                        () -> new CustomException(Result.NOT_FOUND_CONTENT)
                );
        return content;
    }

    private Page<ContentDto.groupListPagePostsDto> getGroupListPagePostsDtos(UserDetails userDetails, Page<Content> contents) {
        return contents.map(
                (Content content) -> {
                    Emotion byContentIdAndUserId = emotionRepository.findByContentIdAndUserId(content.getId(), getUser(userDetails).getId());
                    Long emotionStatus;
                    if (byContentIdAndUserId == null) {
                        emotionStatus = -1L;
                    } else {
                        emotionStatus = byContentIdAndUserId.getEmotionStatus();
                    }
                    String redisKey = content.getId().toString();
                    return ContentDto.groupListPagePostsDto.response(
                            content,
                            content.getContentImages()
                                    .stream()
                                    .map(ContentDto.ImageResponseDto::response)
                                    .toList(),
                            (long) content.getComments().size(),
                            (long) content.getEmotions().size(),
                            content.getEmotions()
                                    .stream()
                                    .map(ContentDto.EmotionResponseDto::response)
                                    .toList(),
                            emotionStatus,
                            Integer.parseInt(redisDao.getValues(redisKey))
                    );
                }
        );
    }

    private void deleteContentImage(List<MultipartFile> multipartFile, ContentDto.UpdateDto request, Content content) {
        if (request.getDeleteContentImageName() != null) {
            request.getDeleteContentImageName().forEach(deleteImageNameDto ->
                    deleteFile(deleteImageNameDto.getImageName())
            );
            request.getDeleteContentImageName().forEach(deleteImageNameDto ->
                    contentImageRepository.delete(contentImageRepository.findByImageName(deleteImageNameDto.getImageName())
                            .orElseThrow(
                                    () -> new CustomException(Result.FAIL)
                            )
                    )
            );
        }

        if (multipartFile != null) {
            uploadFiles(multipartFile, content);
        }
    }

    // validate
    private void validateUpdateContent(Long contentId) {
        if (!contentRepository.existsById(contentId)){
            throw new CustomException(Result.NOT_FOUND_CONTENT);
        }
    }

    private void validateGroupAllListContent(List<Long> groupId) {
        groupId.forEach(
                id -> groupRepository.findById(id).orElseThrow(
                        () -> new CustomException(Result.NOT_FOUND_GROUP)
                )
        );
    }

    private void validateGroupListContent(Long groupId) {
        if (!groupRepository.existsById(groupId)){
            throw new CustomException(Result.NOT_FOUND_GROUP);
        }
    }
}
