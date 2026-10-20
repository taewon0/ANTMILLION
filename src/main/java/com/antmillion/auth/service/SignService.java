package com.antmillion.auth.service;

import java.security.SecureRandom;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.antmillion.auth.dto.AuthMemberDTO;
import com.antmillion.auth.dto.SignUpRequest;
import com.antmillion.auth.jwt.JwtProvider;
import com.antmillion.auth.mapper.AccountMapper;
import com.antmillion.auth.mapper.AuthMemberMapper;
import com.antmillion.auth.mapper.MemberMapper;
import com.antmillion.auth.mapper.SocialMapper;
import com.antmillion.auth.token.RefreshTokenStore;
import com.antmillion.user.dto.AccountDTO;
import com.antmillion.user.dto.MemberDTO;

@Service
public class SignService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int ACCOUNT_NUMBER_DIGITS = 12;
	private static final int ACCOUNT_NUMBER_RETRY = 5;

	private final AuthMemberMapper authMemberMapper;
	private final MemberMapper memberMapper;
	private final AccountMapper accountMapper;
	private final SocialMapper socialMapper;

	private final PasswordEncoder passwordEncoder;
	private final JwtProvider jwtProvider;
	private final RefreshTokenStore refreshStore;

	public SignService(AuthMemberMapper authMemberMapper, MemberMapper memberMapper, AccountMapper accountMapper,
			PasswordEncoder passwordEncoder, JwtProvider jwtProvider, RefreshTokenStore refreshStore,
			SocialMapper socialMapper) {
		this.authMemberMapper = authMemberMapper;
		this.memberMapper = memberMapper;
		this.accountMapper = accountMapper;
		this.socialMapper = socialMapper;
		this.passwordEncoder = passwordEncoder;
		this.jwtProvider = jwtProvider;
		this.refreshStore = refreshStore;
	}

	@Transactional
	public SignUpResult signUpLocal(SignUpRequest req) {

		if (req.getEmail() == null || req.getEmail().trim().isEmpty()) {
			throw new IllegalStateException("이메일이 비어있습니다.");
		}
		if (req.getPassword() == null || req.getPassword().trim().isEmpty()) {
			throw new IllegalStateException("비밀번호가 비어있습니다.");
		}
		if (req.getNickname() == null || req.getNickname().trim().isEmpty()) {
			throw new IllegalStateException("닉네임이 비어있습니다.");
		}

		// 중복 체크
		if (memberMapper.countByEmail(req.getEmail()) > 0) {
			throw new IllegalStateException("이미 사용 중인 이메일입니다.");
		}
		if (memberMapper.countByNickname(req.getNickname()) > 0) {
			throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
		}

		// 회원 정보 저장
		MemberDTO member = new MemberDTO();
		member.setRankId(1);
		member.setPoint(0);
		member.setProvider("LOCAL");
		member.setEmail(req.getEmail());
		member.setPassword(passwordEncoder.encode(req.getPassword()));
		member.setNickname(req.getNickname());

		memberMapper.insertMember(member);

		Long userId = member.getUserId();
		if (userId == null) {
			throw new IllegalStateException("회원가입에 실패했습니다. (userId 생성 실패)");
		}

		// 계좌 정보 저장
		AccountDTO account = new AccountDTO();
		account.setUserId(userId);
		account.setBalance(50_000_000L);

		for (int i = 0; i < ACCOUNT_NUMBER_RETRY; i++) {
			try {
				account.setAccountNumber(generateAccountNumber());
				accountMapper.insertAccount(account);

				return new SignUpResult(userId, account.getAccountNumber(), account.getBalance());
			} catch (DuplicateKeyException e) {
				// account_number 중복 발생 → 재생성 재시도
			}
		}

		throw new IllegalStateException("계좌번호 생성에 실패했습니다. 다시 시도해주세요.");
	}

	@Transactional
	public SignUpResult signUpKakao(long kakaoId, String nickname) {
		if (nickname == null || nickname.trim().isEmpty()) {
			throw new IllegalStateException("닉네임이 비어있습니다.");
		}
		// 닉네임 중복
		if (memberMapper.countByNickname(nickname) > 0) {
			throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
		}
		// 이미 소셜로 가입된 카카오ID면 막기
		Long existsUserId = socialMapper.selectUserIdByKakaoId(kakaoId);
		if (existsUserId != null) {
			throw new IllegalStateException("이미 가입된 카카오 계정입니다.");
		}

		// 회원 정보 저장
		MemberDTO member = new MemberDTO();
		member.setRankId(1);
		member.setPoint(0);
		member.setProvider("KAKAO");
		member.setEmail(null);
		member.setPassword(null);
		member.setNickname(nickname);

		memberMapper.insertMember(member);

		Long userId = member.getUserId();
		if (userId == null)
			throw new IllegalStateException("회원가입 실패(userId 생성 실패)");

		// 계좌 정보 저장
		AccountDTO account = new AccountDTO();
		account.setUserId(userId);
		account.setBalance(50_000_000L);

		for (int i = 0; i < ACCOUNT_NUMBER_RETRY; i++) {
			try {
				account.setAccountNumber(generateAccountNumber());
				accountMapper.insertAccount(account);

				socialMapper.insertSocial(kakaoId, userId);
				return new SignUpResult(userId, account.getAccountNumber(), account.getBalance());

			} catch (DuplicateKeyException e) {
				// 재시도
			}
		}

		throw new IllegalStateException("계좌번호 생성에 실패했습니다. 다시 시도해주세요.");
	}

	public TokenPair login(String email, String rawPassword) {
		email = (email == null) ? "" : email.trim();
		AuthMemberDTO member = authMemberMapper.selectByEmail(email);
		if (member == null) {
			throw new IllegalArgumentException("NO_USER");
		}
		
		if (member.getPassword() == null || !passwordEncoder.matches(rawPassword, member.getPassword())) {
			throw new IllegalArgumentException("BAD_CREDENTIALS");
		}

		Long userId = Long.valueOf(member.getUserId());
		Integer rankId = member.getRankId();
		String provider = member.getProvider();

		String accessJti = UUID.randomUUID().toString();
		String refreshJti = UUID.randomUUID().toString();

		String accessToken = jwtProvider.createAccessToken(userId, rankId, provider, accessJti);
		String refreshToken = jwtProvider.createRefreshToken(userId, refreshJti);

		refreshStore.save(userId, refreshToken, jwtProvider.getRefreshTtlSeconds());

		return new TokenPair(accessToken, refreshToken, jwtProvider.getAccessTtlSeconds(),
				jwtProvider.getRefreshTtlSeconds());
	}

	public TokenPair issueTokensByUserId(long userId) {
		AuthMemberDTO m = authMemberMapper.selectByUserId(userId);
		if (m == null)
			throw new IllegalArgumentException("NO_USER");

		String accessJti = UUID.randomUUID().toString();
		String refreshJti = UUID.randomUUID().toString();

		String accessToken = jwtProvider.createAccessToken(userId, m.getRankId(), m.getProvider(), accessJti);
		String refreshToken = jwtProvider.createRefreshToken(userId, refreshJti);

		refreshStore.save(userId, refreshToken, jwtProvider.getRefreshTtlSeconds());

		return new TokenPair(accessToken, refreshToken, jwtProvider.getAccessTtlSeconds(),
				jwtProvider.getRefreshTtlSeconds());
	}

	// 예: 12자리 숫자 계좌번호
	private String generateAccountNumber() {
		StringBuilder sb = new StringBuilder(ACCOUNT_NUMBER_DIGITS);
		for (int i = 0; i < ACCOUNT_NUMBER_DIGITS; i++) {
			sb.append(RANDOM.nextInt(10));
		}
		return sb.toString();
	}

	public static class TokenPair {
		private final String accessToken;
		private final String refreshToken;
		private final long accessTtlSeconds;
		private final long refreshTtlSeconds;

		public TokenPair(String accessToken, String refreshToken, long accessTtlSeconds, long refreshTtlSeconds) {
			this.accessToken = accessToken;
			this.refreshToken = refreshToken;
			this.accessTtlSeconds = accessTtlSeconds;
			this.refreshTtlSeconds = refreshTtlSeconds;
		}

		public String getAccessToken() {
			return accessToken;
		}

		public String getRefreshToken() {
			return refreshToken;
		}

		public long getAccessTtlSeconds() {
			return accessTtlSeconds;
		}

		public long getRefreshTtlSeconds() {
			return refreshTtlSeconds;
		}
	}

	public static class SignUpResult {
		private final Long userId;
		private final String accountNumber;
		private final Long balance;

		public SignUpResult(Long userId, String accountNumber, Long balance) {
			this.userId = userId;
			this.accountNumber = accountNumber;
			this.balance = balance;
		}

		public Long getUserId() {
			return userId;
		}

		public String getAccountNumber() {
			return accountNumber;
		}

		public Long getBalance() {
			return balance;
		}
	}

	// 이메일 중복 확인
	public boolean isEmailAvailable(String email) {
		return memberMapper.countByEmail(email) == 0;
	}

	// 닉네임 중복 확인
	public boolean isNicknameAvailable(String nickname) {
		return memberMapper.countByNickname(nickname) == 0;
	}
}