# Continuum

Spring Boot 3.3 기반 이벤트 주도 워크플로우 실행 엔진. 외부 워크플로우 엔진/BPMN 의존 없이 직접 구현.

## 핵심 보장

- **상태머신**: `PENDING → RUNNING → COMPLETED | FAILED | COMPENSATED`. 모든 상태/커서를 H2에 영속.
- **정확히-한-번**: `step_records` 테이블에 `(execution_id, step_id, attempt)` 유니크 제약. 같은 시도의 중복 기록은 DB에서 거부됨.
- **크래시 복구**: 부트 시 `ApplicationReadyEvent` 핸들러가 `RUNNING` 상태로 남아있는 실행을 모두 찾아 마지막 `cursor` 부터 자동 재개.
- **재시도**: 스텝별 `RetryPolicy(maxAttempts, initialBackoffMs, multiplier)`. 시도 사이 지수 백오프.
- **보상(SAGA)**: 종단 실패 시 이미 `SUCCEEDED`된 스텝의 `compensateType`을 역순 실행, 결과를 `*#compensate` 기록으로 남김.

## 워크플로우 정의 예 (JSON)

```json
{
  "name": "checkout",
  "steps": [
    {"id": "reserve", "type": "count", "inputs": {"key": "stock"},
     "retry": {"maxAttempts": 3, "initialBackoffMs": 50, "multiplier": 2.0},
     "onFailure": "ABORT",
     "compensateType": "count", "compensateInputs": {"key": "stock-revert"}},
    {"id": "charge", "type": "flaky", "inputs": {"key": "pay"},
     "retry": {"maxAttempts": 5, "initialBackoffMs": 100, "multiplier": 2.0},
     "onFailure": "COMPENSATE"}
  ]
}
```

## 빌드 및 실행

```bash
mvn test                  # 5건 시나리오 테스트
mvn spring-boot:run       # 8090 포트, 데이터는 ./data/continuum (H2 file)
```

## REST API

```bash
# 워크플로 등록
curl -X POST localhost:8090/workflows -H 'content-type: application/json' \
  -d '{"name":"hp","steps":[{"id":"s1","type":"count","inputs":{"key":"k"},"retry":{"maxAttempts":1,"initialBackoffMs":0,"multiplier":1.0},"onFailure":"ABORT"}]}'

# 실행 시작 (동기 실행)
curl -X POST localhost:8090/executions -H 'content-type: application/json' -d '{"workflow":"hp"}'

# 실행 상태 + 이력
curl localhost:8090/executions/1
```

## 테스트 시나리오

| 케이스 | 검증 |
|---|---|
| `happy_path_completes_all_steps` | 두 스텝이 순서대로 실행되고 사이드이펙트가 정확히 2번 적용 |
| `retry_eventually_succeeds` | flaky 핸들러가 2회 실패 후 3회차에 성공, SUCCEEDED는 정확히 1건 |
| `retry_exhausted_triggers_compensation_in_reverse` | 최종 실패 후 보상이 s2 → s1 역순으로 호출 |
| `crash_mid_execution_resumes_from_last_completed_step` | 1스텝만 실행 후 가짜 크래시(`resumeAll`) → 나머지 2개가 이어서 실행, 카운터 합 3 |
| `duplicate_attempt_record_is_rejected` | 이미 SUCCEEDED된 (exec, step, attempt=1) 재기록은 unique 제약으로 거부 |

`mvn test` → 5/5 pass.

## 의도적으로 보류한 항목

- 비동기 실행(스케줄러), 큐 기반 디스패치
- DAG 분기/병렬 (현 MVP는 순차 리스트)
- 클러스터 동시 실행 시 분산 락
- 사용자 보안/RBAC, JWT
