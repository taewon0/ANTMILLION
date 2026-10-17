package com.antmillion.stock.mapper;

import com.antmillion.stock.dto.StockDTO;

import java.util.List;

public interface StockSyncMapper {
    List<String> selectAllCodesForUpdate();
    List<String> selectActiveCodes();
    void upsertStocks(List<StockDTO> stocks);
    int deactivateMissing(List<String> stockCodes);
    int cancelInactiveOrders();
    int countStock();
}
