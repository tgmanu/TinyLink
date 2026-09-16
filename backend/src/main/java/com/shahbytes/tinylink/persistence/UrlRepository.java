package com.shahbytes.tinylink.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UrlRepository extends JpaRepository<UrlEntity, Long> {

    Optional<UrlEntity> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    @Query("""
        SELECT u FROM UrlEntity u
        WHERE u.active = true
          AND u.expiresAt IS NOT NULL
          AND u.expiresAt < :now
        """)
    List<UrlEntity> findExpiredActiveUrls(@Param("now") LocalDateTime now);


    @Modifying
    @Query("""
            UPDATE UrlEntity u
            SET u.clickCount = u.clickCount + 1
            WHERE u.shortCode = :shortCode
              AND u.active = true
            """)
    int incrementClickCount(@Param("shortCode") String shortCode);
}