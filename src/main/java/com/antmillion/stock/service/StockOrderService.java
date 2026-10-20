package com.antmillion.stock.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.antmillion.auth.mapper.AccountMapper;
import com.antmillion.stock.dto.StockOrderDTO;
import com.antmillion.stock.dto.StockOrderResponseDTO;
import com.antmillion.stock.mapper.AssetMapper;
import com.antmillion.stock.mapper.StockMapper;
import com.antmillion.stock.mapper.StockOrderMapper;
import com.antmillion.stock.mapper.TradeLogMapper;
import com.antmillion.user.dto.AccountDTO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class StockOrderService {

	private final StockOrderMapper stockOrderMapper;
	private final TradeLogMapper tradeLogMapper;
	private final AccountMapper accountMapper;
	private final AssetMapper assetMapper;
	private final TransactionTemplate transaction;

    public StockOrderService(StockOrderMapper stockOrderMapper, TradeLogMapper tradeLogMapper,
            AccountMapper accountMapper, AssetMapper assetMapper, PlatformTransactionManager transactionManager) {
        this.stockOrderMapper = stockOrderMapper;
        this.tradeLogMapper = tradeLogMapper;
        this.accountMapper = accountMapper;
        this.assetMapper = assetMapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }
	
	// 주문 생성
    @Transactional
    public boolean createOrder(Long accountId, String stockCode, String transactionType, 
                               String orderType, Integer quantity, Integer orderPrice) {
        
        // 1. 계좌 정보 조회
        AccountDTO account = accountMapper.selectByAccountId(accountId);
        if (account == null) {
            throw new IllegalArgumentException("계좌를 찾을 수 없습니다.");
        }
        
        // 2. 매수/매도 검증(실제 차감 X)
        if ("BUY".equals(transactionType)) {
            long totalPrice = (long) orderPrice * quantity;
            if (account.getBalance() < totalPrice) throw new IllegalArgumentException("잔액이 부족합니다.");
        } else if ("SELL".equals(transactionType)) {
            // 보유 수량에서 현재 '매도 주문 중'인 미체결 수량을 빼고 계산해야 함
            Integer ownedQty = assetMapper.getQuantityByAccountAndStock(accountId, stockCode);
            if (ownedQty == null) ownedQty = 0;

            // DB에서 해당 종목의 (주문수량 - 체결수량) 합계를 가져옴
            Integer orderingQty = stockOrderMapper.getSumUnexecutedQty(accountId, stockCode, "SELL");
            if (orderingQty == null) orderingQty = 0;

            // 실제 팔 수 있는 수량 = 보유량 - 이미 주문 나간 양
            if (ownedQty - orderingQty < quantity) {
                throw new IllegalArgumentException("매도 가능 수량이 부족합니다.");
            }
        }
        
        // 3. 주문 생성
        StockOrderDTO order = new StockOrderDTO();
        order.setAccountId(accountId);
        order.setStockCode(stockCode);
        order.setTransactionType(transactionType);
        order.setOrderType(orderType);
        order.setQuantity(quantity);
        order.setOrderPrice(orderPrice);
        order.setStatus("WAIT");
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        
        return stockOrderMapper.insertOrder(order) > 0;
    }
    
	// 주문 대기 조회
	public List<StockOrderResponseDTO> getWaitOrders(Long accountId) {
		return stockOrderMapper.selectWaitOrders(accountId);
	}
	
	// 주문 취소
	public boolean cancelOrder(Long orderId, Long accountId) {
		return transaction.execute(status -> cancelLockedOrder(orderId, accountId));
	}

	private boolean cancelLockedOrder(Long orderId, Long accountId) {
	    // 주문 존재 여부 및 현재 상태 확인
	    StockOrderResponseDTO order = stockOrderMapper.getOrderByOrderIdForUpdate(orderId);
	    if (order == null) {
	        throw new IllegalArgumentException("존재하지 않는 주문입니다.");
	    }

	    // 이미 체결 완료되었거나 취소된 주문인지 체크
	    if ("COMPLETED".equals(order.getStatus())) {
	        throw new IllegalStateException("이미 전량 체결된 주문은 취소할 수 없습니다.");
	    }
	    if ("CANCEL".equals(order.getStatus())) {
	        throw new IllegalStateException("이미 취소된 주문입니다.");
	    }

	    // 취소 쿼리 실행
	    int result = stockOrderMapper.cancelOrder(orderId, accountId);
	    
	    if (result > 0) {
	        log.info("주문 취소 완료 - orderId: {}, accountId: {}", orderId, accountId);
	        return true;
	    }
	    return false;
	}
	
	// 주문 정정
	public boolean modifyOrder(Long accountId, StockOrderDTO orderRequest) {
		return transaction.execute(status -> modifyLockedOrder(accountId, orderRequest));
	}

	private boolean modifyLockedOrder(Long accountId, StockOrderDTO orderRequest) {
		if (accountId == null) {
			throw new AccessDeniedException("주문을 정정할 권한이 없습니다.");
		}
		if (orderRequest == null || orderRequest.getOrderId() == null || orderRequest.getOrderId() <= 0) {
			throw new IllegalArgumentException("올바른 주문 ID가 필요합니다.");
		}
		if (orderRequest.getQuantity() == null || orderRequest.getQuantity() <= 0
				|| orderRequest.getOrderPrice() == null || orderRequest.getOrderPrice() <= 0) {
			throw new IllegalArgumentException("정정 수량과 가격은 양의 정수여야 합니다.");
		}
		StockOrderResponseDTO original = stockOrderMapper.getOrderByOrderIdForUpdate(orderRequest.getOrderId());
		if (original == null) {
			throw new IllegalArgumentException("존재하지 않는 주문입니다.");
		}
		if (!accountId.equals(original.getAccountId())) {
			throw new AccessDeniedException("주문을 정정할 권한이 없습니다.");
		}
		if (!"WAIT".equals(original.getStatus())) {
			throw new IllegalArgumentException("대기 중인 주문만 정정할 수 있습니다.");
		}
	    // 이미 체결된 수량 합산 조회 (TradeLog 이용)
	    int executedQty = tradeLogMapper.getExecutedQuantitiesForUpdate(original.getOrderId())
	            .stream().mapToInt(Integer::intValue).sum();
	    
	    // 이미 체결된 수량보다 적게 정정하는 건 절대 금지
	    if (orderRequest.getQuantity() < executedQty) {
	        throw new IllegalArgumentException("정정 수량은 이미 체결된 수량(" + executedQty + "주)보다 적을 수 없습니다.");
	    }
	    orderRequest.setAccountId(accountId);
	    
	    return stockOrderMapper.updateOrder(orderRequest) > 0;
	}
}
