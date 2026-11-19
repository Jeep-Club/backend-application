# Jeep Club Backend

Backend oficial do sistema **Jeep Club**, desenvolvido em Java utilizando Spring Boot.

O projeto fornece uma API REST responsável pelo gerenciamento de associados, dependentes, autenticação, permissões, eventos e demais recursos do sistema.

---

# Tecnologias

- Java 17
- Spring Boot 4
- Spring Security
- JWT
- Spring Data JPA
- MySQL
- Flyway (migrations versionadas)
- Maven
- Swagger / OpenAPI

---

# Arquitetura

O projeto segue uma arquitetura inspirada em **Arquitetura Hexagonal (Ports & Adapters)**, separando claramente:

- API
- Application
- Domain
- Infrastructure

Essa divisão facilita manutenção, testes e evolução do sistema.

Antes de qualquer feature ou fix, consulte o
[índice de arquitetura e governança documental](docs/architecture/README.md).
Ele aponta para o padrão de módulos, as regras globais de desenvolvimento e as
fontes especializadas de Platform.

---

# Requisitos

Antes de executar o projeto é necessário possuir instalado:

- Java 17+
- Maven 3.9+ (opcional, pois existe Maven Wrapper)
- MySQL 8+

---

# Instalação

Para desenvolver com **MySQL no Docker, backend no IntelliJ e frontend com
`npm run dev`**, siga o [tutorial do ambiente local](deploy/LOCAL.md).
Ele usa `compose.local.yaml` e um banco persistente na porta `3307`.
A evolução de schema usa Flyway com Hibernate `validate`; consulte a
[auditoria, estratégia de baseline e runbook](docs/database/README.md) antes de
atualizar um banco existente.

## 1. Clone o repositório

```bash
git clone https://github.com/Jeep-Club/backend-application.git

cd backend-application
```

---

## 2. Configure o ambiente local

O fluxo recomendado usa **MySQL no Docker** e **Spring Boot no IntelliJ**.
Siga [o tutorial local](deploy/LOCAL.md) para criar os arquivos privados
a partir dos exemplos já versionados:

- `deploy/mysql-local.env.example` → `.env.local`: porta e credenciais do MySQL
  consumidas pelo `compose.local.yaml`.
- `src/main/resources/application-dev.properties.example` →
  `src/main/resources/application-dev.properties`: datasource, segredo JWT e
  bootstrap do Spring Boot no perfil `dev`.

O `.env.example` da raiz atende à **stack Docker de deploy** (`compose.yaml`),
não substitui o exemplo de propriedades do Spring para desenvolvimento local.
Os arquivos reais são ignorados pelo Git; não versione senhas, tokens, chaves
ou dados pessoais de configuração.

O `src/main/resources/application.properties` contém configurações comuns,
defaults e proteções de Flyway/Hibernate (`validate`); **não edite esse arquivo
para definir credenciais locais**. Gere uma chave JWT exclusivamente local com
pelo menos 32 bytes e configure `security.jwt.secret` na cópia privada do
template. Não reutilize segredos da VPS.

---

## 3. Execute

O CORS pode ser configurado no deploy por variáveis de ambiente, sem alterar o
`application.properties` ou gerar outro build:

| Variável | Configuração |
| --- | --- |
| `APP_CORS_ALLOWED_ORIGINS` | Origins permitidas, separadas por vírgula |
| `APP_CORS_ALLOWED_METHODS` | Métodos permitidos, separados por vírgula |
| `APP_CORS_ALLOWED_HEADERS` | Headers permitidos, separados por vírgula |
| `APP_CORS_ALLOW_CREDENTIALS` | Envio de credenciais (`true` ou `false`) |
| `APP_CORS_MAX_AGE` | Cache do preflight, em segundos |

Exemplo para duas origins: `APP_CORS_ALLOWED_ORIGINS=https://app.jeepclub.com.br,https://admin.jeepclub.com.br`.
Os valores padrão estão em `src/main/resources/application.properties`.

Linux/macOS

```bash
./mvnw spring-boot:run
```

Windows

```cmd
mvnw.cmd spring-boot:run
```

---

# Documentação da API

Swagger e OpenAPI estão desativados em `application.properties`, inclusive no
perfil `dev`. O frontend local pode ser usado para testar os fluxos da API.
Consulte [o tutorial de ambiente local](deploy/LOCAL.md) e
[as regras de acesso pelo frontend](deploy/FRONTEND-ACCESS.md).

---

# Estrutura do Projeto

```text
src/main/java/com/jeepclub/backend
 ├── iam
 ├── memberships
 ├── dependents
 ├── health
 ├── vehicles
 ├── tools
 ├── billing
 ├── shared
 └── platform
```

Cada módulo é organizado em camadas seguindo os princípios da Arquitetura Hexagonal.

---

# Licença

Este projeto é distribuído sob a licença GNU General Public License v3.0.

Veja o arquivo LICENSE.
