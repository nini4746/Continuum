# Event-Driven Workflow Platform
## (Spring 기반 백엔드 토이프로젝트 과제 명세)

---

## 1. 프로젝트 개요

### 1.1 목적
본 프로젝트의 목적은 **이벤트 기반 워크플로 실행 플랫폼**을 설계·구현하는 것이다.  
이 플랫폼은 단순한 CRUD API 서버가 아니라, **복잡한 업무 흐름을 정의·실행·추적·복구할 수 있는 백엔드 시스템**을 목표로 한다.

본 과제는 다음 역량을 검증한다.

- 복잡한 상태 전이 모델링 능력
- 트랜잭션 경계에 대한 정확한 이해
- 장애를 전제로 한 설계
- 장기 유지보수를 고려한 구조 설계
- Spring Framework 내부 메커니즘 이해

---

## 2. 시스템 개요

### 2.1 핵심 개념
- **Workflow**: 하나의 업무 흐름 정의
- **Step**: Workflow를 구성하는 실행 단위
- **Execution**: 실제 실행 중인 Workflow 인스턴스
- **Event**: 상태 전이를 유발하는 신호
- **Policy**: 실행 여부를 결정하는 규칙

---

## 3. 필수 기능 요구사항

### 3.1 Workflow Definition Service

#### 3.1.1 Workflow DSL
Workflow는 JSON 또는 YAML 기반 DSL로 정의되어야 한다.

**필수 요소**
- workflow_id
- version
- steps
- transitions
- failure_policy

**Step 타입**
- SYNC_STEP
- ASYNC_STEP
- CONDITIONAL_STEP
- PARALLEL_STEP
- HUMAN_APPROVAL_STEP

**요구 조건**
- 순환 참조 검증
- 존재하지 않는 Step 참조 방지
- Step 타입별 필수 필드 검증
- 정의 변경 시 기존 실행과 충돌 방지

#### 3.1.2 Workflow Versioning
- 실행 중인 Workflow는 정의 변경의 영향을 받지 않아야 한다.
- 신규 실행만 최신 버전을 사용한다.
- 과거 정의 이력은 반드시 보존한다.

---

### 3.2 Execution Engine

#### 3.2.1 상태 머신
Execution은 다음 상태를 반드시 포함해야 한다.

- CREATED
- RUNNING
- WAITING
- FAILED
- COMPLETED
- CANCELED

상태 전이는 명시적으로 정의된 경우만 허용된다.

#### 3.2.2 정확히 한 번 실행 보장
- 동일 이벤트 중복 수신 시 중복 실행 방지
- 서버 재시작 후에도 중복 실행 금지

#### 3.2.3 장애 복구
- 서버 강제 종료 후 재기동 시 중단된 Execution 자동 복구
- 마지막 안전 지점부터 재개

---

### 3.3 Retry & Compensation

#### 3.3.1 Retry 정책
- 최대 재시도 횟수
- 지수 백오프
- 실패 유형별 재시도 여부 분기

#### 3.3.2 Compensation
- Step 단위 보상 작업 정의
- 부분 성공 상태 추적
- 보상 실패 시 상태 관리

---

### 3.4 Event System

#### 3.4.1 내부 이벤트 버스
- Step 완료 이벤트
- 실패 이벤트
- 타임아웃 이벤트

**요구 사항**
- 비동기 처리
- 이벤트 손실 방지

---

### 3.5 Policy & Permission Engine

#### 3.5.1 Policy DSL
Role 기반 접근 제어는 금지한다.

정책 조건 예시:
- 사용자 속성
- 요청 데이터
- 시간 조건
- 과거 실행 이력

#### 3.5.2 Runtime 평가
- 요청 시 정책 평가
- 정책 변경 즉시 반영 (재시작 없이)

---

### 3.6 Audit / History / Replay

#### 3.6.1 Audit Log
- 모든 상태 전이 기록
- 누가 / 언제 / 왜 변경했는지 기록

#### 3.6.2 Replay
- 과거 Execution 실행 흐름 재현
- 디버깅 목적의 재생 기능

---

### 3.7 Admin / 운영 기능

**필수 기능**
- Execution 강제 중단
- 특정 Step 재실행
- 강제 상태 변경 (로그 필수)
- Dead Execution 탐지
- 실행 통계 조회

---

## 4. 비기능 요구사항

### 4.1 트랜잭션
- 모든 상태 변경은 트랜잭션 내에서 수행
- 이벤트 발행과 상태 변경의 정합성 보장

### 4.2 동시성
- 동일 Workflow 다중 실행 가능
- 경쟁 상태 방지

### 4.3 테스트
- 상태 전이 단위 테스트 필수
- 장애 시나리오 테스트 필수
- 재시작 복구 테스트 필수

---

## 5. 기술 제약

### 필수
- Java + Spring 기반
- JPA 또는 JDBC 직접 사용
- RDBMS 사용

### 금지
- 외부 Workflow Engine 사용
- BPMN 엔진 사용
- SaaS 의존 구조

---

## 6. 산출물

- 전체 소스 코드
- README.md (설계 의도, 트레이드오프, 한계점 포함)
- Architecture 문서
- Workflow DSL 예제
- 장애 시나리오 문서

---

## 7. 평가 기준

- 기능 개수 ❌
- 설계 일관성 ⭕
- 장애 대응 ⭕
- 유지보수 난이도 ⭕
- 설명 가능성 ⭕⭕⭕

---

## 8. 예상 소요 시간

- 최소 400시간
- 설계 실패 시 600시간 이상

---

⚠️ 이 과제는 완성이 목적이 아니다.  
운영 중 문제를 상상하며 계속 고쳐 나가는 과정 자체가 과제의 본질이다.
