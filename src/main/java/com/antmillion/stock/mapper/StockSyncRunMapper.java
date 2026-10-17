package com.antmillion.stock.mapper;

import com.antmillion.stock.dto.SyncResult;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface StockSyncRunMapper {
    int failAbandonedBefore(@Param("date") LocalDate date, @Param("finished") LocalDateTime finished);
    void insertIfAbsent(@Param("date") LocalDate date, @Param("runId") String runId,
                        @Param("started") LocalDateTime started);
    int startAttempt(@Param("date") LocalDate date, @Param("runId") String runId,
                     @Param("started") LocalDateTime started);
    String lockRun(@Param("date") LocalDate date);
    int complete(@Param("date") LocalDate date, @Param("runId") String runId,
                 @Param("finished") LocalDateTime finished, @Param("result") SyncResult result);
    int fail(@Param("date") LocalDate date, @Param("runId") String runId,
             @Param("finished") LocalDateTime finished, @Param("failureType") String failureType);
}
