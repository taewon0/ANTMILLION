package com.antmillion.stock.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.antmillion.auth.mapper.AccountMapper;
import com.antmillion.stock.dto.StockOrderModifyRequestDTO;
import com.antmillion.stock.dto.StockOrderResponseDTO;
import com.antmillion.stock.service.StockOrderService;
import com.antmillion.user.dto.AccountDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/stock")
public class StockOrderController {

    private final StockOrderService stockOrderService;
    private final AccountMapper accountMapper;

    /**
     * 주식 주문 API
     */
    @PostMapping("/order")
    public ResponseEntity<Map<String, Object>> createOrder(
            @RequestBody Map<String, Object> orderRequest) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
        	Long accountId = currentAccountId();
            
            String stockCode = (String) orderRequest.get("stockCode");
            String transactionType = (String) orderRequest.get("transactionType");
            String orderType = (String) orderRequest.get("orderType");
            Integer quantity = (Integer) orderRequest.get("quantity");
            Integer orderPrice = (Integer) orderRequest.get("orderPrice");
            
            log.info("주문 요청 - accountId: {}, stockCode: {}, type: {}, qty: {}, price: {}", 
                    accountId, stockCode, transactionType, quantity, orderPrice);
            
            // 주문 처리
            boolean result = stockOrderService.createOrder(
                    accountId, stockCode, transactionType, orderType, quantity, orderPrice);
            
            if (result) {
                response.put("success", true);
                response.put("message", "주문이 완료되었습니다.");
                log.info("주문 성공");
            } else {
                response.put("success", false);
                response.put("message", "주문 처리 중 오류가 발생했습니다.");
                log.warn("주문 실패");
            }
            
        } catch (IllegalArgumentException e) {
            log.error("주문 검증 실패: {}", e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("주문 오류", e);
            response.put("success", false);
            response.put("message", "주문 중 오류가 발생했습니다: " + e.getMessage());
        }
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 주문 대기 API
     */
    @GetMapping("/pending-list")
    public ResponseEntity<List<StockOrderResponseDTO>> getPendingList() {
    	Long accountId = currentAccountId();

        List<StockOrderResponseDTO> list = stockOrderService.getWaitOrders(accountId);
        return ResponseEntity.ok(list);
    }
    
    /**
     * 주문 취소 API
     */
    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@RequestBody Map<String, Long> request) {
        Map<String, Object> response = new HashMap<>();
        
        Long accountId = currentAccountId();
        Long orderId = request.get("orderId");

        boolean success = stockOrderService.cancelOrder(orderId, accountId);
        
        response.put("success", success);
        response.put("message", success ? "주문이 취소되었습니다." : "취소 가능한 상태가 아닙니다.");
        return ResponseEntity.ok(response);
    }
    
    /**
     * 주문 정정 API
     */
    @PostMapping("/modify")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> modifyOrder(@RequestBody StockOrderModifyRequestDTO orderRequest) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (currentUserId() == null) {
                response.put("success", false);
                response.put("message", "로그인이 필요합니다.");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            Long accountId = currentAccountId();
            if (accountId == null) {
                throw new AccessDeniedException("주문을 정정할 권한이 없습니다.");
            }
            boolean result = stockOrderService.modifyOrder(accountId, orderRequest.toOrder());
            response.put("success", result);
            response.put("message", result ? "정정이 완료되었습니다." : "정정 실패");
        } catch (AccessDeniedException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "알 수 없는 오류 발생");
        }
        return ResponseEntity.ok(response);
    }

    private Long currentAccountId() {
        Long userId = currentUserId();
        if (userId == null) return null;

        AccountDTO account = accountMapper.selectByUserId(userId);
        return (account == null) ? null : account.getAccountId();
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (Exception e) {
            return null;
        }
    }
}
