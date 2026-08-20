package kr.trendstage.persistence.type;

/** reports.status (PG enum report_status). OPEN → EXPLAINING(1차 처리) → DECIDED(최종 결정). */
public enum ReportStatus { OPEN, EXPLAINING, DECIDED }
