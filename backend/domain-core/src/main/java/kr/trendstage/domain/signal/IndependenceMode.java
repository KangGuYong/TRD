package kr.trendstage.domain.signal;

/**
 * 제보자 독립성 압축 모드(SP4 S5). 기본 OFF.
 * DEVICE_OR_IP는 통신사 NAT로 무관한 유저가 같은 IP를 쓰는 오탐 위험이 있다 — 실사례 백테스트 전엔 켜지 않는다.
 */
public enum IndependenceMode { OFF, DEVICE, DEVICE_OR_IP }
