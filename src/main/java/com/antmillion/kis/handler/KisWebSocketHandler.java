package com.antmillion.kis.handler;

import static com.antmillion.kis.constant.KisWebSocketTrId.isAskBidTrId;
import static com.antmillion.kis.constant.KisWebSocketTrId.isPresentPriceTrId;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.antmillion.kis.manager.KisWebSocketManager;
import com.antmillion.kis.service.KisRealtimeMessageAdapter;
import com.antmillion.marketdata.provider.RealtimeMarketDataReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/** 연결·세션 검증과 시스템 응답을 처리하고 시세 프레임은 KIS 변환 경계로 전달합니다. */
@Slf4j
public class KisWebSocketHandler extends TextWebSocketHandler {
	private final KisWebSocketManager manager;
	private final ObjectMapper objectMapper;
	private final KisRealtimeMessageAdapter messageAdapter;
	private final long connectionAttempt;

	public KisWebSocketHandler(KisWebSocketManager manager, ObjectMapper objectMapper,
			RealtimeMarketDataReceiver receiver, long connectionAttempt) {
		this.manager = manager;
		this.objectMapper = objectMapper;
		this.messageAdapter = new KisRealtimeMessageAdapter(receiver);
		this.connectionAttempt = connectionAttempt;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		if (connectionAttempt != 0) manager.connectionEstablished(session, connectionAttempt);
		log.info("### [KIS 웹소켓] 핸드셰이크 성공 및 연결 완료 ###");
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		if (connectionAttempt != 0 && !manager.messageReceived(session)) return;
		// 역할: 데이터 수신 응답 및 처리 (OnMessage)
		String payload = message.getPayload();
		// JSON 응답은 TR·종목·성공 여부로 구독 대기 요청에 연결합니다.
		if (payload.trim().startsWith("{")) {
			handleSystemResponse(session, payload);
			return;
		}
		if (connectionAttempt != 0 && !manager.isCurrentSession(session)) return;

		// 3. 실시간 실무 데이터 처리 (문자열 파싱)
		try {
			messageAdapter.onMessage(payload);
		} catch (Exception e) {
			log.error("### [ERROR] 데이터 처리 오류: {} ###", e.getMessage(), e);
		}
	}

	@Override
	protected void handlePongMessage(WebSocketSession session, PongMessage message) {
		if (connectionAttempt != 0) manager.messageReceived(session);
	}

	private void handleSystemResponse(WebSocketSession session, String payload) {
		if (manager == null) return;
		try {
			JsonNode response = objectMapper.readTree(payload);
			JsonNode header = response.path("header");
			String trId = header.path("tr_id").asText();
			if ("PINGPONG".equals(trId)) {
				manager.echoHeartbeat(session, payload);
				return;
			}
			if (!isAskBidTrId(trId) && !isPresentPriceTrId(trId)) return;
			String stockCode = header.path("tr_key").asText();
			JsonNode body = response.path("body");
			JsonNode result = body.path("rt_cd");
			if (!stockCode.matches("[A-Za-z0-9]{6}") || !result.isTextual() || result.asText().isEmpty()) return;
			boolean success = "0".equals(result.asText());
			String message = body.path("msg1").asText();
			// 공식 샘플의 UNSUB 구분을 따르며 방향이 불명확한 성공 응답은 승인하지 않습니다.
			if (success && !message.startsWith("SUBSCRIBE") && !message.startsWith("UNSUBSCRIBE")) return;
			manager.acknowledge(session, stockCode, trId, success, message.startsWith("UNSUBSCRIBE"));
		} catch (Exception e) {
			// 파서 예외에는 응답 원문·복호화 key가 포함될 수 있어 예외 종류만 남깁니다.
			log.warn("KIS system response ignored: {}", e.getClass().getSimpleName());
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.warn("KIS WebSocket transport error: {}", exception.getClass().getSimpleName());
		manager.connectionLost(session);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		log.info("KIS WebSocket closed: {}", status.getCode());
		manager.connectionLost(session);
	}

}
