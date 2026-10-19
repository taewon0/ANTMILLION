package com.antmillion.stock.mapper;

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

}
