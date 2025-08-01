# CodeCompanion

CodeCompanion is a Slack bot application built with Kotlin and Spring Boot for side projects. It helps teams interact with various services through Slack commands and events, streamlining communication and workflow automation.

## Features
- **Slack Event Handling**: Process various Slack events including mentions, messages, and interactive components
- **Slash Command Processing**: Execute custom commands directly from Slack with the "/" prefix
- **Event-Driven Architecture**: Utilize Kafka for asynchronous event processing and communication between services
- **Meeting Management**: Schedule, organize, and manage team meetings directly from Slack
- **User Management**: Track user interactions and manage permissions within the application
- **Interactive Components**: Create and process interactive elements like buttons, dropdowns, and modals

## Tech Stack
- **Kotlin** (v2.1.21) & **Spring Boot** (v3.5.3)
- **Java** (v21)
- **Clean Architecture**:
  - Domain: Core business logic and entities
  - Application: Use cases and service orchestration
  - Infrastructure: External frameworks and implementations
- **Slack SDK** (v1.45.3): For Slack API integration
- **Kafka**: For event processing and messaging
- **Kotest** (v5.9.1): For testing

## Project Structure
```
CodeCompanion/
├── application/         # Application layer with controllers and services
├── domain/              # Domain layer with core business logic
│   ├── command/         # Command handling
│   └── meet/            # Meeting management
├── infrastructure/      # Infrastructure layer with external implementations
│   ├── impl/            # Implementations of domain interfaces
│   └── templates/       # Templates for Slack messages and modals
```

## Getting Started
1. **Clone the repository**
   ```
   git clone https://github.com/TrulyNotMalware/CodeCompanion.git
   cd CodeCompanion
   ```

2. **Configure Slack API credentials**
   - Create a Slack App at [api.slack.com/apps](https://api.slack.com/apps)
   - Set up necessary permissions and event subscriptions
   - Update application properties with your credentials

3. **Build the application**
   ```
   ./gradlew :application:build
   ```

4. **Run the application**
   ```
   ./gradlew :application:bootRun
   ```

5. **Verify the installation**
   - Invite your bot to a Slack channel
   - Test with a slash command or mention

## Contributing
Contributions are welcome! Please feel free to submit a Pull Request.


---

# CodeCompanion

CodeCompanion은 사이드 프로젝트를 위한 Kotlin과 Spring Boot로 구축된 슬랙 봇 애플리케이션입니다. 팀이 슬랙 명령어와 이벤트를 통해 다양한 서비스와 상호작용할 수 있도록 도와주며, 커뮤니케이션과 워크플로우 자동화를 간소화합니다.

## 기능
- **슬랙 이벤트 처리**: 멘션, 메시지, 상호작용 컴포넌트 등 다양한 슬랙 이벤트 처리
- **슬래시 명령어 처리**: "/" 접두사를 사용하여 슬랙에서 직접 사용자 정의 명령어 실행
- **이벤트 기반 아키텍처**: 비동기 이벤트 처리 및 서비스 간 통신을 위한 Kafka 활용
- **미팅 관리**: 슬랙에서 직접 팀 미팅 일정을 잡고, 조직하고, 관리
- **사용자 관리**: 사용자 상호작용 추적 및 애플리케이션 내 권한 관리
- **상호작용 컴포넌트**: 버튼, 드롭다운, 모달과 같은 상호작용 요소 생성 및 처리

## 기술 스택
- **Kotlin** (v2.1.21) 및 **Spring Boot** (v3.5.3)
- **Java** (v21)
- **클린 아키텍처**:
  - 도메인: 핵심 비즈니스 로직 및 엔티티
  - 애플리케이션: 유스케이스 및 서비스 오케스트레이션
  - 인프라스트럭처: 외부 프레임워크 및 구현
- **Slack SDK** (v1.45.3): Slack API 통합용
- **Kafka**: 이벤트 처리 및 메시징용
- **Kotest** (v5.9.1): 테스트용


## 프로젝트 구조
```
CodeCompanion/
├── application/         # 컨트롤러 및 서비스가 있는 애플리케이션 계층
├── domain/              # 핵심 비즈니스 로직이 있는 도메인 계층
│   ├── command/         # 명령어 처리
│   └── meet/            # 미팅 관리
├── infrastructure/      # 외부 구현이 있는 인프라스트럭처 계층
│   ├── impl/            # 도메인 인터페이스 구현
│   └── templates/       # 슬랙 메시지 및 모달 템플릿
```

## 시작하기
1. **저장소 복제**
   ```
   git clone https://github.com/TrulyNotMalware/CodeCompanion.git
   cd CodeCompanion
   ```

2. **Slack API 자격 증명 구성**
   - [api.slack.com/apps](https://api.slack.com/apps)에서 Slack 앱 생성
   - 필요한 권한 및 이벤트 구독 설정
   - 애플리케이션 속성을 자격 증명으로 업데이트

3. **애플리케이션 빌드**
   ```
   ./gradlew :application:build
   ```

4. **애플리케이션 실행**
   ```
   ./gradlew :application:bootRun
   ```

5. **설치 확인**
   - 슬랙 채널에 봇 초대
   - 슬래시 명령어나 멘션으로 테스트

## 라이센스
MIT License
