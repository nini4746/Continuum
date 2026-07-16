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
mvn test                  # 8건 시나리오 테스트
mvn spring-boot:run       # 8090 포트, 데이터는 ./data/continuum (H2 file)
```

## 운영 추가 기능

- 비동기 실행: `POST /executions/async` (스레드풀 기반)
- 실행별 `ReentrantLock` — 같은 execution 동시 `run()` 호출 직렬화
- `RetryStrategy` SPI — `ExponentialBackoffRetry` 기본, 커스텀 교체 가능
- Micrometer + OpenTelemetry 트레이싱(OTLP)
- 무한루프 가드 `max-steps=10000`
- 에러 메시지 sanitize (클래스명만 기록, 스택트레이스 제외)

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
| `failed_step_records_sanitized_error_class_only` | 실패 기록에 스택트레이스 없이 예외 클래스만 |
| `async_run_completes_independently` | 비동기 실행 종료 후 상태 일치 |
| `concurrent_run_calls_on_same_execution_do_not_interleave` | 같은 execution에 동시 `run()` 호출 직렬화 |
| `duplicate_attempt_record_is_rejected` | 이미 SUCCEEDED된 (exec, step, attempt=1) 재기록은 unique 제약으로 거부 |

`mvn test` → 20/20 pass.

DAG/락 추가 테스트(`DagEngineTests`, `DistributedLockTests`):
- 사이클/unknown 의존 거부, diamond DAG happy path, 병렬 분기, ABORT 시 후속 step 미실행, COMPENSATE 시 역위상 unwind, 순차 워크플로의 sequential path 보존 (DAG 6건)
- 단일 holder 진입/해제, 경합 시 timeout, 잘못된 token unlock no-op, 다중 키 독립성, 8 thread × 300회 mutual exclusion (락 5건)

## 스펙 대비 구현 범위 (정직 고지)

이 저장소는 원 스펙 `Continuum.md`의 **일부만** 구현한 상태다 (핵심 실행 엔진 ~50%). 아래는 스펙이 요구하지만 **아직 구현하지 않은** 항목이다. 이전 README는 이를 명시하지 않아 완성으로 오인될 수 있었다 — 아래로 갭을 명확히 한다.

### 설계상 보류 (스펙 범위 밖으로 명시 결정)

- 비동기 실행 스케줄러, 큐 기반 디스패치 (현재는 스레드풀 직접 실행)
- 사용자 보안/RBAC, JWT
- 외부 분산 락 backend 통합 (Redis/etcd) — 추상화는 완료, 실제 어댑터는 미포함

### 스펙 요구인데 미구현 (미착수 — 후속 작업 필요)

| 스펙 | 기능 | 현 상태 |
|---|---|---|
| §3.1.1 | `CONDITIONAL_STEP` (조건 분기 스텝) | 미구현 — 핸들러/DSL 없음 |
| §3.1.1 | `HUMAN_APPROVAL_STEP` (수동 승인 스텝) | 미구현 — `WAITING` 상태·승인 API 없음 |
| §3.1.1 | DSL `workflow_id` / `transitions` / 최상위 `failure_policy` | 미구현 — `WorkflowDef`는 `name`+`steps`만 |
| §3.1.2 | 워크플로우 **버전 관리** (실행 중 정의 변경 격리, 이력 보존) | 미구현 — `WorkflowEntity`에 version 컬럼 없음 |
| §3.2.1 | 실행 상태 `WAITING` / `CANCELED` | 미구현 — enum에 없음 |
| §3.4 | 내부 이벤트 버스 (step 완료/실패/타임아웃 발행·구독) | 미구현 |
| §3.5 | 정책·권한 엔진 (속성/시간/이력 조건 DSL, 무재시작 반영) | 미구현 — policy 패키지 없음 |
| §3.6.1 | 감사 로그 (누가/언제/왜 상태 전이) | 미구현 — `StepRecord`에 actor/reason 필드 없음 |
| §3.6.2 | Replay (과거 실행 흐름 재현) | 미구현 — 중복검사 훅만 있고 replay 없음 |
| §3.7 | 관리자 기능 (강제 중단/스텝 재실행/강제 상태변경/dead 탐지/통계) | 미구현 — `WorkflowController`는 register/start/get 4개만 |

> 전량 구현은 규모가 커서(대략 수 주) 별도 `명세→그릴링→/goal` 워크플로우로 진행해야 한다. 현재 구현된 부분(상태머신, 정확히-한-번, 크래시 복구, 재시도, 보상, DAG/병렬, 분산 락 추상화)은 위 "핵심 보장" + "DAG/병렬" + "분산 락" 섹션 기준으로 동작하고 테스트(20/20)로 검증된다.

## DAG / 병렬 실행

`StepDef`에 `dependsOn` 필드(없으면 빈 배열, 순차 모드 유지). `WorkflowDef`는 등록 시점에 unknown reference + cycle을 동시에 검증한다. 한 step이라도 dependsOn을 가지면 엔진은 DAG 모드로 진입:
- 의존이 모두 SUCCEEDED인 step을 동시 dispatch
- 별도 `dagStepWorker` 풀 사용 (asyncWorker가 DAG 호출 thread를 들고 있을 때 deadlock 방지)
- 실패 시 ABORT/COMPENSATE/SKIP 의미는 동일 — COMPENSATE는 위상 정렬 역순으로 unwind

## 분산 락 추상화

`DistributedLock` 인터페이스 + `InProcessDistributedLock` 기본 구현(단일 JVM). `tryLock(key, timeout, unit) -> Token`, `unlock(key, token)`. 실제 분산 환경에선 동일 인터페이스를 만족하는 다른 빈을 등록(예: Redis/Lettuce 기반)하면 엔진 변경 없이 갈아끼울 수 있다. 워크플로 실행은 `execution:{id}` 키로 락을 잡으며, `continuum.lock.timeout-ms` 로 대기 시간 설정.
