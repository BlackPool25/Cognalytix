# API Reference

All requests and responses use JSON. Standard paths are prefixed with `/api`. Protected routes require a stateless JWT access token supplied in the `Authorization` header: `Authorization: Bearer <token>`.

---

## 1. Authentication

All authentication endpoints are located under `/api/auth`. Register, login, and refresh endpoints are **public**.

### Register Account
- **Endpoint**: `POST /api/auth/register`
- **Payload**:
  ```json
  {
    "name": "Alex Doe",
    "email": "alex@example.com",
    "password": "SecurePassword123!"
  }
  ```
- **Response**: `200 OK`
  ```json
  {
    "message": "User registered successfully",
    "tokens": {
      "accessToken": "ey...",
      "refreshToken": "opaque-hmac-hash...",
      "tokenType": "Bearer",
      "expiresInSeconds": 900
    },
    "user": {
      "id": "c88f98a3-2287-4b53-a5c7-9e7dfa300d8b",
      "name": "Alex Doe",
      "email": "alex@example.com",
      "role": "USER"
    }
  }
  ```

### Log In
- **Endpoint**: `POST /api/auth/login`
- **Payload**:
  ```json
  {
    "email": "alex@example.com",
    "password": "SecurePassword123!"
  }
  ```
- **Response**: `200 OK` (Same payload as Registration)

### Refresh Access Token
- **Endpoint**: `POST /api/auth/refresh`
- **Payload**:
  ```json
  {
    "refreshToken": "opaque-hmac-hash..."
  }
  ```
- **Response**: `200 OK` (Contains updated `accessToken` and `refreshToken`)

### Log Out (Protected)
- **Endpoint**: `POST /api/auth/logout`
- **Payload**:
  ```json
  {
    "refreshToken": "opaque-hmac-hash..."
  }
  ```
- **Response**: `200 OK` (Invalidates the refresh token in the database)

### Change Password (Protected)
- **Endpoint**: `PUT /api/auth/password`
- **Payload**:
  ```json
  {
    "currentPassword": "SecurePassword123!",
    "newPassword": "NewSuperSecurePassword999!"
  }
  ```
- **Response**: `200 OK`

---

## 2. Journal Entry Endpoints

All journal operations require `ROLE_USER` or `ROLE_ADMIN`.

### Create Journal Entry
- **Endpoint**: `POST /api/journals`
- **Payload**:
  ```json
  {
    "title": "Evening reflections",
    "content": "Had a productive discussion about the microservice boundaries today. Felt excited but also a bit overwhelmed by the dependencies.",
    "entryDate": "2026-06-13T18:00:00Z"
  }
  ```
- **Response**: `200 OK` (Queues analysis asynchronously)
  ```json
  {
    "id": "e30be928-8902-4b71-b552-a54cf8e30bba",
    "title": "Evening reflections",
    "wordCount": 20,
    "analysisStatus": "PENDING",
    "entryDate": "2026-06-13T18:00:00Z"
  }
  ```

### Get Journal Feed (Paginated)
- **Endpoint**: `GET /api/journals?page=0&size=10&sort=entryDate,desc&search=productive`
- **Response**: `200 OK`
  ```json
  {
    "content": [
      {
        "id": "e30be928-8902-4b71-b552-a54cf8e30bba",
        "title": "Evening reflections",
        "wordCount": 20,
        "analysisStatus": "DONE",
        "entryDate": "2026-06-13T18:00:00Z"
      }
    ],
    "totalPages": 1,
    "totalElements": 1,
    "size": 10,
    "number": 0
  }
  ```
  > [!IMPORTANT]
  > To keep responses lightweight, the journal feed endpoint **omits** full entry content, sections, and analysis insights. To display a detailed view, query the entry by ID.

### Get Journal Entry Detail
- **Endpoint**: `GET /api/journals/{id}`
- **Response**: `200 OK`
  ```json
  {
    "id": "e30be928-8902-4b71-b552-a54cf8e30bba",
    "title": "Evening reflections",
    "content": "Had a productive discussion about the microservice boundaries today. Felt excited but also a bit overwhelmed by the dependencies.",
    "analysisStatus": "DONE",
    "lastAnalysisError": null,
    "entryDate": "2026-06-13T18:00:00Z",
    "moodAnalysis": {
      "moodLabel": "anxious",
      "intensity": 3,
      "insight": "You are balancing positive project progression against complexity concerns.",
      "copingTip": null,
      "themes": ["microservices", "boundaries", "dependencies"]
    },
    "sections": [
      {
        "sortOrder": 0,
        "content": "Had a productive discussion about the microservice boundaries today.",
        "topicDisplayLabel": "microservices",
        "topicCategory": "topic",
        "emotionDisplayLabel": "excited",
        "emotionCategory": "emotion",
        "intensity": 2
      },
      {
        "sortOrder": 1,
        "content": "Felt excited but also a bit overwhelmed by the dependencies.",
        "topicDisplayLabel": "boundaries",
        "topicCategory": "topic",
        "emotionDisplayLabel": "anxious",
        "emotionCategory": "emotion",
        "intensity": 3
      }
    ]
  }
  ```

### Update Journal Entry
- **Endpoint**: `PUT /api/journals/{id}`
- **Payload**: `{title, content, entryDate}`
- **Response**: `200 OK` (Resets `analysisStatus` to `PENDING` and triggers fresh analysis)

### Force Re-analyze Entry
- **Endpoint**: `POST /api/journals/{id}/reanalyze`
- **Response**: `200 OK` (Forces prior mood analysis and sections to be wiped and schedules fresh run)

### Soft Delete Entry
- **Endpoint**: `DELETE /api/journals/{id}`
- **Response**: `200 OK`

### Hard Delete Entry (Permanent)
- **Endpoint**: `DELETE /api/journals/{id}/permanent`
- **Response**: `200 OK`

---

## 3. Growth Insights

Insights are computed asynchronously after a successful entry analysis has committed.

### Get Latest Post-Entry Mirror Card
- **Endpoint**: `GET /api/insights/growth/latest?entryId={id}`
- **Response**: `200 OK`
  ```json
  {
    "entryId": "e30be928-8902-4b71-b552-a54cf8e30bba",
    "analysisStatus": "DONE",
    "mirrorReady": true,
    "hasTrajectory": true,
    "patternType": "EMOTION_DRIFT_ON_TOPIC_FAMILY",
    "day": {
      "summaryInsight": "You are balancing positive project progression against complexity concerns.",
      "overallIntensity": 3,
      "dominantMoodLabel": "anxious",
      "themes": ["microservices", "boundaries", "dependencies"],
      "sections": [
        {
          "topicLabel": "microservices",
          "topicFamilyKey": "work_progress",
          "emotionLabel": "excited",
          "emotionFamilyKey": "excitement",
          "intensity": 2,
          "excerpt": "Had a productive discussion..."
        }
      ]
    },
    "trajectory": {
      "kind": "EMOTION_DRIFT_ON_TOPIC_FAMILY",
      "mirrorCard": {
        "headline": "Progress builds alongside growing complexity boundaries.",
        "trajectoryLine": "Your work focus is shifting from pure excitement to anxiety-tinged planning.",
        "dayAnchorLine": "Today was dominated by anxious feelings (intensity 3) regarding boundaries.",
        "integratedBody": "Step back to map out individual tasks. Complex integrations are manageable when parsed systematically.",
        "direction": "GROWTH"
      },
      "trajectoryFacts": {
        "topicFamilyKey": "work_progress",
        "topicDisplayLabel": "microservices",
        "priorDominantEmotionFamily": "excitement",
        "priorDominantAvgIntensity": 2.0,
        "priorDistinctJournalCount": 2,
        "currentDominantEmotionFamily": "anxiety",
        "currentDominantAvgIntensity": 3.0,
        "currentSectionCount": 1
      }
    }
  }
  ```

---

## 4. Analytical Data Export

For third-party analytics (e.g. Power BI, Tableau, or CSV download).

### Flat Journal Section Export
- **Endpoint**: `GET /api/exports/journal-sections?from=2026-06-01T00:00:00Z&to=2026-07-01T00:00:00Z&page=0&size=100`
- **Response**: `200 OK`
  ```json
  {
    "content": [
      {
        "sectionId": "a244439c-51fa-4001-9a2c-f9eef11082a1",
        "entryId": "e30be928-8902-4b71-b552-a54cf8e30bba",
        "entryTitle": "Evening reflections",
        "entryCreatedAt": "2026-06-13T18:00:00Z",
        "sortOrder": 0,
        "topicLabelId": "t1111111-2222-3333-4444-555555555555",
        "topicLabel": "microservices",
        "emotionLabelId": "e1111111-2222-3333-4444-555555555555",
        "emotionLabel": "excited",
        "intensity": 2,
        "excerptContent": "Had a productive discussion about the microservice boundaries today."
      }
    ],
    "totalPages": 1,
    "totalElements": 1,
    "size": 100,
    "number": 0
  }
  ```

---

## 5. Admin Endpoints

Protected routes requiring `ROLE_ADMIN` and inclusion in the server's optional email allowlist (`app.security.admin-allowed-emails`).

### Rotate Server Password Pepper
- **Endpoint**: `PUT /api/admin/security/password-pepper`
- **Payload**:
  ```json
  {
    "adminPassword": "CurrentAdminPassword",
    "newPepper": "64_character_hex_string_representing_256_bits..."
  }
  ```
- **Response**: `200 OK` (Re-encrypts the singleton database pepper record)

### Deactivate User
- **Endpoint**: `PUT /api/admin/users/{id}/deactivate`
- **Response**: `200 OK` (Sets `is_active = false`. That account will no longer be able to log in or refresh tokens).

### Reset User Password
- **Endpoint**: `PUT /api/admin/users/{id}/password`
- **Payload**:
  ```json
  {
    "newPassword": "NewGeneratedPassword123!"
  }
  ```
- **Response**: `200 OK`
