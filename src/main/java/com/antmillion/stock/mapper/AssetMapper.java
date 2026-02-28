package com.antmillion.stock.mapper;

import com.antmillion.stock.dto.AssetDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AssetMapper {

    /**
     * 특정 계좌의 특정 종목 보유 수량 조회
     */
    Integer getQuantityByAccountAndStock(@Param("accountId") Long accountId, @Param("stockCode") String stockCode);

    /**
     * 매수: 수량 증가, 평균 단가 계산 및 갱신
     */
    int upsertAssetBuy(@Param("accountId") Long accountId, @Param("stockCode") String stockCode,
            @Param("quantity") Integer quantity, @Param("avgPrice") Integer avgPrice,
            @Param("purchaseAmount") Long purchaseAmount);

    /**
     * 매도: 수량 감소, 총액 감소, 평균 단가는 유지됨
     */
    int updateAssetSell(@Param("accountId") Long accountId, @Param("stockCode") String stockCode,
            @Param("quantity") Integer quantity);

    /**
     * 매도 체결 시 돈 입금
     */
    int addBalance(@Param("accountId") Long accountId, @Param("amount") long amount);
    
    /**
     * 매수 체결 시 돈 차감
     */
    int minusBalance(@Param("accountId") Long accountId, @Param("amount") long amount);
    
    /**
     * 수량이 0이 된 주식 데이터 제거
     */
    int deleteZeroQuantityAsset(@Param("accountId") Long accountId, @Param("stockCode") String stockCode);

    /**
     * 매도 전 보유 정보 조회 (실현손익 계산용)
     */
    AssetDTO getAssetForSell(@Param("accountId") Long accountId, @Param("stockCode") String stockCode);

    /**
     * 매도: 차감원가를 직접 받아서 처리
     */
    int updateAssetSellWithCostBasis(@Param("accountId") Long accountId,
                                     @Param("stockCode") String stockCode,
                                     @Param("quantity") Integer quantity,
                                     @Param("costBasis") Long costBasis);

    /**
     * 상장 폐지된 종목은 보유주식에서 제거
     */
    int deleteByStockCodes(@Param("codes") List<String> codes);
}
