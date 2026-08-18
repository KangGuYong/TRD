package kr.trendstage.apipublic.web;

/** 존재하지 않거나 병합되어 사라진(MERGED) 트렌드 항목 조회(404). */
public class TrendNotFoundException extends RuntimeException {
    public TrendNotFoundException(String message) { super(message); }
}
