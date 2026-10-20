package com.antmillion.stock.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.antmillion.stock.dto.AssetDTO;
import com.antmillion.stock.dto.TradeLogDTO;
import com.antmillion.stock.mapper.RealizedPnlMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.antmillion.stock.dto.StockOrderResponseDTO;
import com.antmillion.stock.mapper.AssetMapper;
import com.antmillion.stock.mapper.StockOrderMapper;
import com.antmillion.stock.mapper.TradeLogMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TradingEngineService {
    private final StockOrderMapper stockOrderMapper;
    private final TradeLogMapper tradeLogMapper;
    private final AssetMapper assetMapper;
    private final RealizedPnlMapper realizedPnlMapper;
    private final TransactionTemplate transaction;

    public TradingEngineService(StockOrderMapper stockOrderMapper, TradeLogMapper tradeLogMapper,
            AssetMapper assetMapper, RealizedPnlMapper realizedPnlMapper,
            PlatformTransactionManager transactionManager) {
        this.stockOrderMapper = stockOrderMapper;
        this.tradeLogMapper = tradeLogMapper;
        this.assetMapper = assetMapper;
        this.realizedPnlMapper = realizedPnlMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void processExecution(String stockCode, String transactionType, int currentPrice, int availableQty) {
        if (!"BUY".equals(transactionType) && !"SELL".equals(transactionType)) {
            throw new IllegalArgumentException("체결 주문 방향은 BUY 또는 SELL이어야 합니다.");
        }
        // 비어 있거나 유효하지 않은 호가는 해당 방향만 건너뛰고 다음 호가를 기다립니다.
        if (currentPrice <= 0 || availableQty <= 0) {
            return;
        }
        // 기존 메서드 경계를 유지하며, annotation 활성화 여부와 무관하게 한 호출 전체를 롤백합니다.
        transaction.executeWithoutResult(status -> executeOrders(stockCode, transactionType, currentPrice, availableQty));
    }

    private void executeOrders(String stockCode, String transactionType, int currentPrice, int availableQty) {
        // 체결 가능한 주문 리스트 
        List<StockOrderResponseDTO> orders = stockOrderMapper.findExecutableOrders(stockCode, transactionType, currentPrice);
        
        int remainingMarketQty = availableQty; // 시장 호가 잔량

        for (StockOrderResponseDTO order : orders) {
            if (remainingMarketQty <= 0) break;

            int unexecutedQty = order.getUnexecutedQuantity();
            // 이번에 체결할 수량 (시장 물량 vs 주문 미체결량 중 최소값)
            int actualExecQty = Math.min(unexecutedQty, remainingMarketQty);

            // 1. INSERT 영향 행 수와 생성된 체결 ID를 구분합니다.
            TradeLogDTO trade = TradeLogDTO.builder().orderId(order.getOrderId())
                    .tradePrice((long) currentPrice).tradeQuantity(actualExecQty).build();
            int inserted = tradeLogMapper.insertTradeLog(trade);
            Long tradeId = trade.getTradeId();
            if (inserted != 1 || tradeId == null || tradeId <= 0) {
                throw new IllegalStateException("체결 기록 또는 생성 ID 확인 실패");
            }

            // 3. 주문 상태 업데이트 (누적 체결량 다시 확인하여 상태 변경)
            int totalExecutedSoFar = order.getExecutedQuantity() + actualExecQty;
            String status = (totalExecutedSoFar >= order.getQuantity()) ? "COMPLETED" : "PARTIAL";
            stockOrderMapper.updateOrderStatus(order.getOrderId(), status);

            // 4. 자산 및 잔액 반영
            long tradeAmount = (long)currentPrice * actualExecQty;
            if ("BUY".equals(order.getTransactionType())) {
            	// minusBalance의 리턴값(영향받은 행 수)을 확인해서 잔액 부족 체크 필수
            	int rows = assetMapper.minusBalance(order.getAccountId(), tradeAmount);
                if (rows == 0) {
                    // 잔액이 부족하면 이번 호출에서 처리한 앞선 주문까지 함께 롤백합니다.
                    throw new RuntimeException("잔액 부족으로 체결 불가");
                }
                assetMapper.upsertAssetBuy(order.getAccountId(), stockCode, actualExecQty, currentPrice, tradeAmount);
            } else {
                // ===== 매도: 실현손익 계산 및 저장 (보유주식 차감 전) =====
                AssetDTO asset = assetMapper.getAssetForSell(order.getAccountId(), stockCode);
                if (asset == null || asset.getQuantity() == null || asset.getQuantity() <= 0
                        || asset.getQuantity() < actualExecQty) {
                    throw new IllegalStateException("보유 자산 누락 또는 수량 부족으로 매도 체결 불가");
                }
                long totalBuyAmount = asset.getPurchaseAmount();
                int holdingQty = asset.getQuantity();
                double avgPrice = asset.getAvgPrice();

                // 차감원가 계산
                long costBasis;
                if (actualExecQty == holdingQty) {
                    // 전량매도 : 차감원가 = 남은 총 매수금액 전부
                    costBasis = totalBuyAmount;
                } else {
                    // 부분매도 : 차감원가 = floor(매입금액 * 매도수량 / 보유수량)
                    costBasis = (long) Math.floor((double) totalBuyAmount * actualExecQty / holdingQty);
                }

                // 실현손익 = 매도금액 - 차감원가
                long realizedProfit = tradeAmount - costBasis;

                // 실현손익 테이블에 저장
                String sellDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                realizedPnlMapper.insertRealizedPnl(
                    stockCode,
                    tradeId,
                        order.getAccountId(),
                        sellDate,
                        actualExecQty,
                        tradeAmount,
                        costBasis,
                        realizedProfit,
                        avgPrice
                );

                // 1. 매도: 주식 출고 및 잔액 입금
                int assetRows = assetMapper.updateAssetSellWithCostBasis(order.getAccountId(), stockCode, actualExecQty, costBasis);
                if (assetRows != 1) {
                    throw new IllegalStateException("매도 자산 차감 실패");
                }
                int balanceRows = assetMapper.addBalance(order.getAccountId(), tradeAmount);
                if (balanceRows != 1) {
                    throw new IllegalStateException("매도 잔고 입금 실패");
                }
                
                // 2. DB에 반영된 최신 수량을 다시 확인
                Integer currentQty = assetMapper.getQuantityByAccountAndStock(order.getAccountId(), stockCode);
                
                // 3. 수량이 0이거나 null이면 확실히 삭제 처리
                if (currentQty == null || currentQty <= 0) {
                    assetMapper.deleteZeroQuantityAsset(order.getAccountId(), stockCode);
                }
            }

            remainingMarketQty -= actualExecQty; // 시장 물량 소진
            log.info("주문ID {}: {}주 체결 (남은 시장물량: {})", order.getOrderId(), actualExecQty, remainingMarketQty);
        }
    }
}
