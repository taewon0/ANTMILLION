package com.antmillion.kis.handler;

import static com.antmillion.kis.constant.KisWebSocketTrId.isAskBidTrId;
import static com.antmillion.kis.constant.KisWebSocketTrId.isPresentPriceTrId;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.stock.service.TradingEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * KIS 웹소켓 데이터 처리 (KisWebSocketHandler)
 * 1. afterConnectionEstablished: 연결 성공 로그 출력 (구독 메시지는 여기서 보내지 않음)
 * 2. handleTextMessage: 서버로부터 메시지 수신 시 자동 실행
 * - PINGPONG: 수신 즉시 에코(Echo) 응답을 보내 세션 유지 및 Manager의 하트비트 갱신
 * - 실시간 데이터: 파이프(|)와 캐럿(^) 기호로 분리된 문자열을 파싱
 * 3. 데이터 전파:
 * - 파싱된 Map 데이터를 SimpMessagingTemplate을 통해 프론트엔드(STOMP)로 즉시 전송
 * - TradingEngineService를 호출하여 실시간 체결 로직(매매 확인) 실행
 */

@Slf4j
public class KisWebSocketHandler extends TextWebSocketHandler {
	private final KisWebSocketManager manager;
	private final String approvalKey;
	private final SimpMessagingTemplate messagingTemplate; // STOMP 전송용
	private ObjectMapper objectMapper;
	private final TradingEngineService tradingEngineService;

	public KisWebSocketHandler(KisWebSocketManager manager, String approvalKey, SimpMessagingTemplate messagingTemplate,
			ObjectMapper objectMapper, TradingEngineService tradingEngineService) {
		this.manager = manager;
		this.approvalKey = approvalKey;
		this.messagingTemplate = messagingTemplate;
		this.objectMapper = objectMapper;
		this.tradingEngineService = tradingEngineService;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		log.info("### [KIS 웹소켓] 핸드셰이크 성공 및 연결 완료 ###");
	}

	// 숫자를 안전하게 추출하는 메서드
	private int parseSafeInt(String val) {
		if (val == null || val.trim().isEmpty())
			return 0;
		try {
			// 공백 제거 및 숫자가 아닌 문자 제거 후 파싱
			return Integer.parseInt(val.trim());
		} catch (NumberFormatException e) {
			log.warn("숫자 파싱 실패: [{}]", val);
			return 0;
		}
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		// 역할: 데이터 수신 응답 및 처리 (OnMessage)
		String payload = message.getPayload();
		// 1. PINGPONG 처리
		if (payload.contains("PINGPONG")) {
			manager.updateLastHeartbeatTime(); // 수신 시간 갱신
			log.info("### PINGPONG 수신 완료 ###");
			try {
				session.sendMessage(new TextMessage(payload));
			} catch (IOException e) {
				System.err.println("PINGPONG 응답 전송 실패: " + e.getMessage());
				manager.monitorHealth(); // 재연결
			}
			return;
		}

		// 2. JSON 응답 처리 (최초 구독 성공 알림 등)
		if (payload.startsWith("{")) {
			return;
		}

		// 3. 실시간 실무 데이터 처리 (문자열 파싱)
		try {
			String[] parts = payload.split("\\|");
			String trId = parts[1];
			if (isPresentPriceTrId(trId)) {
				String[] data = parts[3].split("\\^");
				if (data[2].equals("0")) {
		               return;
		            }
				// [0], [2], [4], [5], [22]
				Map<String, String> tradeData = new HashMap<>();
				tradeData.put("mkscShrnIscd", data[0]); // 종목코드
				tradeData.put("stckPrpr", data[2]); // 현재가
				tradeData.put("prdySign", data[3]); // 전일 대비 부호
				tradeData.put("prdyVrss", data[4]); // 전일 대비
				tradeData.put("prdyCtrt", data[5]); // 대비율
				tradeData.put("shnuRate", data[22]); // 매수 비율

				String stockCode = data[0];

				// STOMP 전송
				messagingTemplate.convertAndSend("/topic/kis-trade/present" + stockCode, tradeData); // 실시간 체결가
//				log.info("### [실시간 체결가] 종목: {} ###\n{}", stockCode, tradeData);
			} else if (isAskBidTrId(trId)) {
				String[] data = parts[3].split("\\^");
				Map<String, String> askBidData = new HashMap<>();
				askBidData.put("mkscShrnIscd", data[0]); // 종목코드
				askBidData.put("askP1", data[3]);
				askBidData.put("askP2", data[4]);
				askBidData.put("askP3", data[5]);
				askBidData.put("askP4", data[6]);
				askBidData.put("askP5", data[7]);
				askBidData.put("bidP1", data[13]);
				askBidData.put("bidP2", data[14]);
				askBidData.put("bidP3", data[15]);
				askBidData.put("bidP4", data[16]);
				askBidData.put("bidP5", data[17]);
				askBidData.put("askPrsqn1", data[23]);
				askBidData.put("askPrsqn2", data[24]);
				askBidData.put("askPrsqn3", data[25]);
				askBidData.put("askPrsqn4", data[26]);
				askBidData.put("askPrsqn5", data[27]);
				askBidData.put("bidPrsqn1", data[33]);
				askBidData.put("bidPrsqn2", data[34]);
				askBidData.put("bidPrsqn3", data[35]);
				askBidData.put("bidPrsqn4", data[36]);
				askBidData.put("bidPrsqn5", data[37]);

				String stockCode = data[0];
				messagingTemplate.convertAndSend("/topic/kis-trade/ask-bid" + stockCode, askBidData);
				//log.info("### [실시간 호가] 종목: {} ###\n{}", stockCode, askBidData);
				int bestBidPrice = parseSafeInt(data[13]); // 매수 1호가
				int bestBidQty = parseSafeInt(data[33]); // 매수 1호가 잔량

				int bestAskPrice = parseSafeInt(data[3]); // 매도 1호가
				int bestAskQty = parseSafeInt(data[23]); // 매도 1호가 잔량
				// 매수 주문 체결 확인 (최우선 매도호가에 내 매수 주문이 닿았는가)
				tradingEngineService.processExecution(stockCode, bestAskPrice, bestAskQty);
				// 매도 주문 체결 확인 (최우선 매수호가에 내 매도 주문이 닿았는가)
				tradingEngineService.processExecution(stockCode, bestBidPrice, bestBidQty);
			} else {
				log.info("### [알림] 현재가/호가 데이터 아님 ###");
			}
		} catch (Exception e) {
			log.error("### [ERROR] 데이터 처리 오류: {} ###", e.getMessage(), e);
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.error("### [ERROR] 웹소켓 통신 에러: {} ###", exception.getMessage());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		int closeCode = status.getCode();
		String closeReason = status.getReason();
		log.info("### KIS 연결 종료: {} ###", status.getReason());

		if (closeCode == CloseStatus.NORMAL.getCode() || closeCode == CloseStatus.GOING_AWAY.getCode()) {
			log.info("### [NORMAL] 정상적인 연결 종료 - 재연결 안 함 ###");
		} else if (closeCode == CloseStatus.TOO_BIG_TO_PROCESS.getCode()) {
			log.info("### [TOO_BIG] 메시지 크기 초과로 연결 종료 - 재연결 ###");
			manager.clearSubscriptionState();
			manager.monitorHealth();
		}


	}

}
