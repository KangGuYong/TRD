package kr.trendstage.domain.verdict;

/**
 * 제보 시계열에서 뽑은 판정 입력. 외부 지표 없이 제보 자체가 판정 근거다(CLAUDE.md R1 개정).
 *
 * @param distinctSubmitters 최초 제보자를 포함해, 서로 다른 유저가 낸 유효(비VOID) 제보 수
 * @param distinctPlatforms  제보에 적힌 최초 목격 플랫폼(source_platform)의 서로 다른 값 수
 */
public record SubmissionSignal(int distinctSubmitters, int distinctPlatforms) {}
