package com.antmillion.stock.mapper;

import com.antmillion.stock.dto.InterestDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface InterestMapper {

    //관심종목 추가
    int insertInterest(InterestDTO interest);

    //관심종목 삭제
    int deleteInterest(@Param("stockCode") String stockCode, @Param("accountId") Long accountId);

    //특정 계정의 관심종목 조회
    List<String> selectInterestStockCodesByAccountId(@Param("accountId") Long accountId);

    // 특정 종목이 관심종목인지 확인
    int countInterest(@Param("stockCode") String stockCode, @Param("accountId") Long accountId);

    // 상장폐지 된 종목은 관심종목에서 삭제
    int deleteByStockCodes(@Param("codes") List<String> codes);
}
