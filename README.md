# TinyLink

TinyLink is a URL shortener backend built with Java and Spring Boot.

The project demonstrates URL shortening, custom aliases, URL expiration, persistent storage with PostgreSQL, Redis caching, Redis-backed rate limiting, click analytics, scheduled cleanup, and containerized local development using Docker Compose.

---

## Features

- Create short URLs from long URLs
- Generate random Base62 short codes
- Support custom aliases
- Optional URL expiration
- Redirect short URLs to original URLs
- Persistent URL storage using PostgreSQL
- Persistent click-event analytics
- Track click counts
- Store click details such as IP address, user agent, and referrer
- Redis caching for fast URL lookups
- Redis-backed rate limiting
- Soft deletion of URLs
- Automatic cleanup of expired URLs
- Dockerized Spring Boot backend
- Docker Compose setup with PostgreSQL and Redis
- Persistent Docker volumes for PostgreSQL and Redis

---

## Architecture


                    ┌──────────────────────┐
                    │    Spring Boot API   │
                    │       Backend        │
                    └──────────┬───────────┘
                               │
                 ┌─────────────┴─────────────┐
                 │                           │
                 ▼                           ▼
        ┌─────────────────┐         ┌─────────────────┐
        │   PostgreSQL    │         │      Redis      │
        │                 │         │                 │
        │ URLs            │         │ URL Cache       │
        │ Click Events    │         │ Rate Limiting   │
        └─────────────────┘         └─────────────────┘
        
## Project structure

TinyLink/
├── backend/
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── com/
│   │       │       └── shahbytes/
│   │       │           └── tinylink/
│   │       │               ├── config/
│   │       │               ├── controllers/
│   │       │               ├── dto/
│   │       │               ├── models/
│   │       │               ├── persistence/
│   │       │               │   ├── ClickEventEntity.java
│   │       │               │   ├── ClickEventRepository.java
│   │       │               │   ├── UrlEntity.java
│   │       │               │   └── UrlRepository.java
│   │       │               └── services/
│   │       └── resources/
│   │           └── application.yaml
│   │
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── pom.xml
│
├── frontend/
│   └── ...
│
└── README.md
