package com.antmillion.stock.mapper;

import com.antmillion.stock.dto.StockDTO;

import java.util.List;

public interface StockSyncMapper {
    void truncateTemp();
    void insertTempStock(StockDTO stock);
    void insertTempStockBatch(List<StockDTO> stocks); // 배치 삽입 추가
    int insertNewStocks();
    int deleteOldStocks();
    int countStock();
    List<String> selectDelistedCodes();
}
