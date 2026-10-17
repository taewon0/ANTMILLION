package com.antmillion.stock.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SyncResult {
    private int beforeCount;
    private int afterCount;
    private int insertedCount;
    private int deletedCount;
    private int deactivatedCount;
    private int cancelledOrderCount;
    private int totalDownloaded;
    private long executionTimeMs; // 소요 시간 (밀리초)
}
