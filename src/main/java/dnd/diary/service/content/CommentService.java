package dnd.diary.service.content;

import dnd.diary.domain.comment.Comment;
import dnd.diary.domain.content.Content;
import dnd.diary.domain.content.Emotion;
import dnd.diary.domain.user.User;
import dnd.diary.dto.content.CommentDto;
import dnd.diary.dto.content.ContentDto;
import dnd.diary.enumeration.Result;
import dnd.diary.exception.CustomException;
import dnd.diary.repository.content.CommentLikeRepository;
import dnd.diary.repository.content.CommentRepository;
import dnd.diary.repository.content.ContentRepository;
import dnd.diary.repository.content.EmotionRepository;
import dnd.diary.repository.user.UserRepository;
import dnd.diary.response.CustomResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentService {
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final ContentRepository contentRepository;
    private final EmotionRepository emotionRepository;
    private final CommentLikeRepository commentLikeRepository;

    @Transactional
    public CustomResponseEntity<CommentDto.AddCommentDto> commentAdd(
            UserDetails userDetails, Long contentId, CommentDto.AddCommentDto request
    ) {
        validateCommentAdd(contentId);
        return CustomResponseEntity.success(
                CommentDto.AddCommentDto.response(
                        commentRepository.save(
                                Comment.builder()
                                        .commentNote(request.getCommentNote())
                                        .user(getUser(userDetails))
                                        .content(getContent(contentId))
                                        .sticker(null)
                                        .build()
                        )
                )
        );
    }

    @Transactional
    public CustomResponseEntity<CommentDto.pagePostsCommentDto> commentPage(
            UserDetails userDetails, Long contentId, Integer page
    ) {
        validateCommentPage(contentId);
        return CustomResponseEntity.success(
                CommentDto.pagePostsCommentDto.response(
                        getPageCommentDtos(userDetails, getComments(contentId, page)),
                        getEmotionResponseDtos(contentId),
                        emotionRepository.countByContentId(contentId),
                        commentRepository.countByContentId(contentId)
                )
        );
    }

    // method
    private Page<Comment> getComments(Long contentId, Integer page) {
        return commentRepository.findByContentId(
                contentId, PageRequest.of(page - 1, 10, Sort.Direction.DESC, "createdAt")
        );
    }

    private Page<CommentDto.pageCommentDto> getPageCommentDtos(UserDetails userDetails, Page<Comment> comments) {
        return comments.map((Comment comment) -> CommentDto.pageCommentDto.response(
                        comment, commentLikeRepository.existsByCommentIdAndUserId(
                                comment.getId(), getUser(userDetails).getId()
                        )
                )
        );
    }

    private User getUser(UserDetails userDetails) {
        return userRepository.findOneWithAuthoritiesByEmail(userDetails.getUsername())
                .orElseThrow(
                        () -> new CustomException(Result.NOT_FOUND_USER)
                );
    }

    private Content getContent(Long contentId) {
        return contentRepository.findById(contentId)
                .orElseThrow(
                        () -> new CustomException(Result.NOT_FOUND_CONTENT)
                );
    }

    private List<ContentDto.EmotionResponseDto> getEmotionResponseDtos(Long contentId) {
        List<Emotion> byContentId = emotionRepository.findByContentId(contentId);
        List<ContentDto.EmotionResponseDto> emotion = byContentId.stream().map(ContentDto.EmotionResponseDto::response).toList();
        return emotion;
    }

    // validate
    private void validateCommentPage(Long contentId) {
        if (!contentRepository.existsById(contentId)){
            throw new CustomException(Result.NOT_FOUND_CONTENT);
        }
    }

    private void validateCommentAdd(Long contentId) {
        if (!contentRepository.existsById(contentId)){
            throw new CustomException(Result.NOT_FOUND_CONTENT);
        }
    }
}
