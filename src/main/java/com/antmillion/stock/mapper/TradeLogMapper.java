package com.antmillion.stock.mapper;

import java.util.List;
import com.antmillion.stock.dto.TradeLogDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TradeLogMapper {

    /**
     * 영향 행 수를 반환하며 생성된 tradeId는 전달한 DTO에 설정합니다.
     */
    int insertTradeLog(TradeLogDTO trade);

    /**
     * 특정 주문번호(order_id)에 대해 지금까지 체결된 총 수량 조회
     */
    int getTotalExecutedQty(@Param("orderId") Long orderId);

    // 주문 행 잠금 후 REPEATABLE READ의 과거 스냅샷 대신 최신 체결량을 읽습니다.
    List<Integer> getExecutedQuantitiesForUpdate(@Param("orderId") Long orderId);

}
