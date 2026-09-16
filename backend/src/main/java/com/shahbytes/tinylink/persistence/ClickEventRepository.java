package com.shahbytes.tinylink.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEventEntity, Long> {

    List<ClickEventEntity> findByUrlOrderByTimestampDesc(UrlEntity url);
}