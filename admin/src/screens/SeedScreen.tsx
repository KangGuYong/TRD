import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import { registerSeed, useSeedAccuracy } from "../api/hooks";
import type { SeedSubmissionRequest } from "../api/types";
import { Card, Btn, StateView } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

const CATEGORIES: SeedSubmissionRequest["category"][] = ["MEME", "PRODUCT", "PERSON_CHANNEL", "CHALLENGE", "SLANG", "ETC"];
const CONFIDENCE_OPTIONS: SeedSubmissionRequest["confidence"][] = [10, 30, 50];

const EMPTY: SeedSubmissionRequest = {
  name: "", category: "MEME", platform: "", evidenceUrl: "", confidence: 10, oneLine: "",
};

export default function SeedScreen() {
  const { role } = useRole();
  const queryClient = useQueryClient();
  const q = useSeedAccuracy();

  const [form, setForm] = useState<SeedSubmissionRequest>(EMPTY);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };
  const canRegister = CAN.seedRegister(role);
  const canSubmit = canRegister && form.name.trim() !== "" && form.platform.trim() !== ""
    && form.evidenceUrl.trim() !== "" && form.oneLine.trim() !== "" && !busy;

  const submit = async () => {
    setBusy(true);
    try {
      const result = await registerSeed(form);
      queryClient.invalidateQueries({ queryKey: ["admin", "seed-accuracy"] });
      setForm(EMPTY);
      flash(`시딩 등록 완료: ${result.canonicalName}`);
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "등록에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 1000 }}>
      <Card>
        <b style={{ fontSize: 13 }}>시딩 등록</b>
        <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 6, lineHeight: 1.6 }}>
          운영진 직접 등록분은 score_ledger에 반영되지 않습니다(is_seed=true) — 초기 데이터 확보용입니다.
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, marginTop: 16 }}>
          <Field label="트렌드명">
            <input value={form.name} disabled={!canRegister}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              style={inputStyle} />
          </Field>
          <Field label="카테고리">
            <select value={form.category} disabled={!canRegister}
              onChange={(e) => setForm({ ...form, category: e.target.value as SeedSubmissionRequest["category"] })}
              style={inputStyle}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </Field>
          <Field label="플랫폼">
            <input value={form.platform} disabled={!canRegister}
              onChange={(e) => setForm({ ...form, platform: e.target.value })}
              style={inputStyle} />
          </Field>
          <Field label="근거 URL">
            <input value={form.evidenceUrl} disabled={!canRegister}
              onChange={(e) => setForm({ ...form, evidenceUrl: e.target.value })}
              style={inputStyle} />
          </Field>
          <Field label="확신도">
            <div style={{ display: "flex", gap: 8 }}>
              {CONFIDENCE_OPTIONS.map((c) => (
                <button key={c} disabled={!canRegister} onClick={() => setForm({ ...form, confidence: c })}
                  style={{ ...inputStyle, cursor: canRegister ? "pointer" : "not-allowed",
                    background: form.confidence === c ? C.ink : "#fff", color: form.confidence === c ? "#fff" : C.ink }}>
                  {c}
                </button>
              ))}
            </div>
          </Field>
          <Field label="한 줄 설명">
            <input value={form.oneLine} disabled={!canRegister}
              onChange={(e) => setForm({ ...form, oneLine: e.target.value })}
              style={inputStyle} />
          </Field>
        </div>

        <div style={{ marginTop: 16 }}>
          <Btn tone="primary" disabled={!canSubmit} onClick={submit}>등록</Btn>
          {!canRegister && (
            <span style={{ marginLeft: 10, font: "500 11.5px Pretendard", color: C.faint }}>OPERATOR 이상만 등록할 수 있습니다</span>
          )}
        </div>
      </Card>

      <Card style={{ marginTop: 12 }}>
        <b style={{ fontSize: 13 }}>담당자별 적중률</b>
        <StateView query={q}>
          {(rows) => (
            <table style={{ width: "100%", marginTop: 14, borderCollapse: "collapse" }}>
              <thead>
                <tr style={{ textAlign: "left", borderBottom: `1px solid ${C.line}` }}>
                  {["담당자", "HIT", "MISS", "판정건수", "TI"].map((h) => (
                    <th key={h} style={{ padding: "8px 10px", font: "600 11.5px Pretendard", color: C.faint }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.operatorName} style={{ borderBottom: `1px solid ${C.line}` }}>
                    <td style={{ padding: "10px" }}>{r.operatorName}</td>
                    <td style={{ padding: "10px" }}>{r.hit}</td>
                    <td style={{ padding: "10px" }}>{r.miss}</td>
                    <td style={{ padding: "10px" }}>{r.judged}</td>
                    <td style={{ padding: "10px", font: "700 12.5px ui-monospace, monospace",
                      color: r.trustIndex >= 0.5 ? C.rising : r.trustIndex < 0.3 ? C.fading : C.ink }}>
                      {Math.round(r.trustIndex * 100)}%
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </StateView>
      </Card>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  padding: "8px 10px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard", width: "100%",
};

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: "block" }}>
      <div style={{ font: "600 11.5px Pretendard", color: C.sub, marginBottom: 6 }}>{label}</div>
      {children}
    </label>
  );
}
