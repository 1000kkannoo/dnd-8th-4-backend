package dnd.diary.enumeration;

import lombok.Getter;

@Getter
public enum Result {

	OK(0, "성공"),
	FAIL(-1, "실패"),
	FAIL_IMAGE_UPLOAD(2000, "파일 업로드 실패"),
	NOT_FOUND_USER(2100, "존재하지 않는 사용자"),
	NOT_FOUND_GROUP(2101, "존재하지 않는 그룹"),
	LOW_MIN_GROUP_NAME_LENGTH(2102, "그룹 이름 최소 글자(1자) 미만"),
	HIGH_MAX_GROUP_NAME_LENGTH(2103, "그룹 이름 최대 글자(12자) 초과"),
	NO_USER_GROUP_LIST(2104, "가입한 그룹이 없는 경우");

	private final int code;
	private final String message;

	Result(int code, String message) {
		this.code = code;
		this.message = message;
	}

	public Result resolve(int code) {
		for (Result result : values()) {
			if (result.getCode() == code) {
				return result;
			}
		}
		return null;
	}
}