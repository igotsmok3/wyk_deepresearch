# Repository Guidelines

## Project Structure & Module Organization
The backend is a Spring Boot app under `src/main/java/com/alibaba/cloud/ai/example/deepresearch`, organized by responsibility: `controller`, `service`, `node`, `dispatcher`, `rag`, `tool`, `config`, and `util`. Runtime resources live in `src/main/resources`, especially `prompts/` and `application-*.yml`. Backend tests are in `src/test/java`, with test config in `src/test/resources`. The Vue 3 frontend is isolated in `ui-vue3/` with source in `ui-vue3/src`, public assets in `ui-vue3/public`, and Cypress support files in `ui-vue3/cypress`. Docs and diagrams live in `docs/` and `imgs/`.

## Build, Test, and Development Commands
Use the repository root for backend work:

- `mvn clean install -DskipTests` builds the Java application and packages artifacts.
- `mvn spring-boot:run` starts the backend on the default Spring Boot port.
- `mvn test -DskipTests=false` runs backend tests; the override is required because Surefire is configured to skip tests by default.
- `mvn checkstyle:check` validates Java style against `tools/src/checkstyle/checkstyle.xml`.
- `mvn spotless:apply` removes unused imports and applies configured cleanup.

Use `ui-vue3/` for frontend work:

- `npm install` installs frontend dependencies.
- `npm run dev` starts the Vite dev server.
- `npm run build` runs type-checking and produces a production bundle.
- `npm run test:unit` runs Vitest.
- `npm run lint` runs ESLint with auto-fix.

## Coding Style & Naming Conventions
Target Java 17. Follow Spring Java Format and the checked-in Checkstyle rules; prefer 4-space indentation, `UpperCamelCase` for classes, and `lowerCamelCase` for methods and fields. Keep package names lowercase. For Vue and TypeScript, use the existing ESLint and Prettier setup, keep component files in `PascalCase` such as `Login.vue`, and use descriptive store and service names such as `MessageStore.ts` and `reports.ts`.

## Testing Guidelines
Backend tests use JUnit and should follow the existing `*Test.java` naming pattern. Put unit and controller tests under `src/test/java` beside the related package path. Frontend unit tests run with Vitest in a `jsdom` environment; end-to-end flows use Cypress via the scripts in `ui-vue3/package.json`. Add tests with each behavior change and cover both happy-path and configuration-sensitive cases.

## Commit & Pull Request Guidelines
Recent history favors short conventional commits such as `docs: update`, `fix: add missing frontend dependencies`, and `feat(docs): ...`. Use `type(scope): summary` when a scope adds clarity. Before opening a PR, rebase onto `main`, run the relevant Maven and npm checks, and summarize the user-visible impact. Link related issues when applicable and include screenshots or short recordings for UI changes.
