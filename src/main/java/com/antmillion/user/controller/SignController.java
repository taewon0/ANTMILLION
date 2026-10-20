package com.antmillion.user.controller;

import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.antmillion.auth.dto.SignUpRequest;
import com.antmillion.auth.jwt.CookieUtil;
import com.antmillion.auth.service.SignService;
import com.antmillion.auth.service.SignService.SignUpResult;
import com.antmillion.auth.service.SignService.TokenPair;
import com.antmillion.auth.support.SignupSessionKeys;
import com.antmillion.auth.terms.TermsProvider;
import com.antmillion.kakao.token.KakaoSignupStore;
import com.antmillion.mail.service.EmailVerificationService;

@Controller
@RequestMapping
public class SignController {

	private static final Pattern EMAIL_RULE = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
	private static final Pattern PW_RULE = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)\\S{8,}$");

	private final TermsProvider termsProvider;
	private final SignService signService;
	private final KakaoSignupStore kakaoSignupStore;
	private final EmailVerificationService emailVerificationService;

	public SignController(SignService signService, TermsProvider termsProvider, KakaoSignupStore kakaoSignupStore,
			EmailVerificationService emailVerificationService) {
		this.termsProvider = termsProvider;
		this.signService = signService;
		this.kakaoSignupStore = kakaoSignupStore;
		this.emailVerificationService = emailVerificationService;
	}

	// 로그인 화면
	@GetMapping("/login")
	public String loginForm(Authentication authentication) {
		if (isAuthenticated(authentication)) return "redirect:/";
		return "login/login";
	}

	@PostMapping("/login")
	public String loginSubmit(@RequestParam("email") String email, @RequestParam("password") String password,
			HttpServletResponse response, RedirectAttributes ra) {
		email = n(email);

		if (email.isEmpty() || !EMAIL_RULE.matcher(email).matches()) {
			ra.addFlashAttribute("loginError", "이메일 형식이 올바르지 않습니다.");
			ra.addFlashAttribute("email", email);
			return "redirect:/login";
		}
		if (password == null || password.isBlank()) {
		    ra.addFlashAttribute("error", "비밀번호를 입력해 주세요.");
		    return "login/login";
		}
		if (password == null || !PW_RULE.matcher(password).matches()) {
		    ra.addFlashAttribute("error", "비밀번호는 8자리 이상, 영문/숫자를 포함하고 공백 없이 특수문자를 사용할 수 있습니다.");
		    return "login/signup";
		}
		try {
			TokenPair tokens = signService.login(email, password);

			// RT/AT 쿠키 세팅
			CookieUtil.addHttpOnlyCookie(response, "RT", tokens.getRefreshToken(), tokens.getRefreshTtlSeconds());
			CookieUtil.addHttpOnlyCookie(response, "AT", tokens.getAccessToken(), tokens.getAccessTtlSeconds());

			return "redirect:/";
		} catch (Exception e) {
			ra.addFlashAttribute("loginError", "이메일/비밀번호를 확인해 주세요.");
			ra.addFlashAttribute("email", email);
			return "redirect:/login";
		}
	}

	// 회원가입 1단계 화면
	@GetMapping("/signup")
	public String signupForm(Model model, HttpSession session, Authentication authentication) {
		if (isAuthenticated(authentication)) return "redirect:/";
		session.removeAttribute(SignupSessionKeys.EMAIL_VERIFIED_EMAIL);
		session.removeAttribute(SignupSessionKeys.EMAIL_VERIFIED_AT);

		clearKakaoSignupIfExists(session);

		SignUpRequest saved = (SignUpRequest) session.getAttribute(SignupSessionKeys.SIGNUP_FORM);
		model.addAttribute("form", saved != null ? saved : new SignUpRequest());
		return "login/signup";
	}

	// 회원가입 1단계 처리 -> 세션에 저장 후 step2로 이동
	@PostMapping("/signup")
	public String signupStep1Submit(@ModelAttribute("form") SignUpRequest form,
			@RequestParam("passwordConfirm") String passwordConfirm, HttpSession session, Model model) {
		// 로컬 회원가입 시작 = 카카오 가입 진행중이면 취소 처리
		clearKakaoSignupIfExists(session);

		String email = n(form.getEmail());
		String password = n(form.getPassword());
		passwordConfirm = n(passwordConfirm);

		try {
			// 입력 검증
			validateLocalStep1(email, password, passwordConfirm);

			// 이메일 중복
			if (!signService.isEmailAvailable(email)) {
				throw new IllegalStateException("이미 사용 중인 이메일입니다.");
			}

			// 이메일 인증
			String verifiedEmail = (String) session.getAttribute(SignupSessionKeys.EMAIL_VERIFIED_EMAIL);
			if (verifiedEmail == null || !verifiedEmail.equalsIgnoreCase(email)) {
				throw new IllegalStateException("이메일 인증을 완료해 주세요.");
			}

			// Step1 정보 세션에 저장
			SignUpRequest sessionForm = new SignUpRequest();
			sessionForm.setEmail(email);
			sessionForm.setPassword(password);
			session.setAttribute(SignupSessionKeys.SIGNUP_FORM, sessionForm);

			return "redirect:/signup/step2";

		} catch (Exception e) {
			model.addAttribute("error", e.getMessage());
			model.addAttribute("form", form);
			return "login/signup";
		}
	}

	@GetMapping("/signup/check-email")
	@ResponseBody
	public Map<String, Object> checkEmail(@RequestParam("email") String email) {
		email = n(email);

		if (email.isEmpty()) {
			return Map.of("available", false, "message", "이메일을 입력하세요.");
		}
		if (!EMAIL_RULE.matcher(email).matches()) {
			return Map.of("available", false, "message", "이메일 형식이 올바르지 않습니다.");
		}

		boolean ok = signService.isEmailAvailable(email);
		return Map.of("available", ok, "message", ok ? "사용 가능한 이메일입니다." : "이미 사용 중인 이메일입니다.");
	}

	@GetMapping("/signup/step2")
	public String signupStep2Form(HttpSession session, RedirectAttributes ra, Model model, Authentication authentication) {
		if (isAuthenticated(authentication)) return "redirect:/";
		SignUpRequest sessionForm = (SignUpRequest) session.getAttribute(SignupSessionKeys.SIGNUP_FORM);
		if (sessionForm == null) {
			ra.addFlashAttribute("error", "이메일이나 비밀번호 입력이 잘못되었습니다.");
			return "redirect:/signup";
		}

		model.addAttribute("termsText", termsProvider.getServiceTerms());
		model.addAttribute("privacyText", termsProvider.getPrivacyTerms());
		return "login/signup_step2";
	}

	// 회원가입 2단계 처리 -> DB insert + 완료 페이지로
	@PostMapping("/signup/step2")
	public String signupStep2Submit(@RequestParam("nickname") String nickname,
			@RequestParam(value = "agreeTerms", required = false) String agreeTerms,
			@RequestParam(value = "agreePrivacy", required = false) String agreePrivacy, HttpServletRequest request,
			Model model, RedirectAttributes ra, HttpServletResponse response) {
		try {
			HttpSession session = request.getSession(false);
	        if (session == null) return "redirect:/signup";
			
			SignUpRequest sessionForm = (SignUpRequest) session.getAttribute(SignupSessionKeys.SIGNUP_FORM);
			if (sessionForm == null)
				return "redirect:/signup";

			// 카카오 가입
			String kakaoSignupKey = (String) session.getAttribute(SignupSessionKeys.KAKAO_SIGNUP_KEY);
			boolean isKakao = (kakaoSignupKey != null)
					&& (sessionForm.getEmail() == null || sessionForm.getEmail().trim().isEmpty());

			// 로컬일 때만: 이메일 중복 재검사
			if (!isKakao) {
				String email = n(sessionForm.getEmail());
				if (email == null || email.trim().isEmpty()) {
					session.removeAttribute(SignupSessionKeys.SIGNUP_FORM);
					return "redirect:/signup";
				}
				if (!signService.isEmailAvailable(email.trim())) {
					session.removeAttribute(SignupSessionKeys.SIGNUP_FORM);
					throw new IllegalStateException("이미 사용 중인 이메일입니다. 처음부터 다시 진행하세요.");
				}
			}

			// 공통: 닉네임/약관 체크
			nickname = n(nickname);
			if (nickname.isEmpty()) {
				throw new IllegalStateException("닉네임을 입력하세요.");
			}
			if (!signService.isNicknameAvailable(nickname)) {
				throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
			}
			if (!"Y".equals(agreeTerms) || !"Y".equals(agreePrivacy)) {
				throw new IllegalStateException("필수 약관에 동의해야 합니다.");
			}

			// 카카오 회원가입
			if (isKakao) {
				Long kakaoId = kakaoSignupStore.get(kakaoSignupKey);
				if (kakaoId == null) {
					cleanupSignupSession(request);
					throw new IllegalStateException("카카오 가입 시간이 만료되었습니다. 다시 카카오 로그인을 진행하세요.");
				}

				// DB 저장: 이메일·비밀번호가 없는 회원 정보와 계좌·소셜 연결 정보를 저장합니다.
				SignUpResult result = signService.signUpKakao(kakaoId, nickname);

				// 가입과 동시에 로그인
				TokenPair tokens = signService.issueTokensByUserId(result.getUserId());
				CookieUtil.addHttpOnlyCookie(response, "RT", tokens.getRefreshToken(), tokens.getRefreshTtlSeconds());

				// 임시 데이터 정리
				kakaoSignupStore.delete(kakaoSignupKey);

				ra.addFlashAttribute("signupType", "KAKAO");
				ra.addFlashAttribute("accountNumber", result.getAccountNumber());
				ra.addFlashAttribute("balance", result.getBalance());
				return "redirect:/signup/complete";
			}
			// LOCAL 회원가입 
			sessionForm.setNickname(nickname);
			SignUpResult result = signService.signUpLocal(sessionForm);

			// 가입 완료 시: 이메일 인증 상태/코드 키 정리
			String email = n(sessionForm.getEmail());
			if (!email.isEmpty()) {
				emailVerificationService.clear(email);
			}

			// 혹시 남아있는 카카오 진행 흔적이 있으면 정리
			String kakaoKey = (String) session.getAttribute(SignupSessionKeys.KAKAO_SIGNUP_KEY);
			if (kakaoKey != null) {
			    kakaoSignupStore.delete(kakaoKey);
			}

			ra.addFlashAttribute("signupType", "LOCAL");
			ra.addFlashAttribute("accountNumber", result.getAccountNumber());
			ra.addFlashAttribute("balance", result.getBalance());

			return "redirect:/signup/complete";

		} catch (Exception e) {
			model.addAttribute("error", e.getMessage());
			model.addAttribute("nickname", nickname);
			return "login/signup_step2";
		}
	}

	@GetMapping("/signup/check-nickname")
	@ResponseBody
	public Map<String, Object> checkNickname(@RequestParam("nickname") String nickname) {
        nickname = n(nickname);
        if (nickname.isEmpty()) {
            return Map.of("available", false, "message", "닉네임을 입력하세요.");
        }
        boolean ok = signService.isNicknameAvailable(nickname);
        return Map.of("available", ok, "message", ok ? "사용 가능한 닉네임입니다." : "이미 사용 중인 닉네임입니다.");
    }

	@GetMapping("/signup/complete")
	public String signupComplete(Model model, Authentication authentication, HttpServletRequest request) {
		boolean hasPayload = model.asMap().containsKey("signupType")
	            && model.asMap().containsKey("accountNumber")
	            && model.asMap().containsKey("balance");

	    if (!hasPayload) {
	    	// 새로고침하면
	        // 카카오 가입은 가입과 동시에 로그인 상태(인증됨) -> 메인으로
	        // 로컬 가입은 미로그인 상태 -> 로그인 화면으로
	        return isAuthenticated(authentication) ? "redirect:/" : "redirect:/login";
	    }
	    cleanupSignupSession(request);
	    return "login/signup_complete";
	}

	@PostMapping("/signup/complete")
	public String signupCompleteSubmit() {
		return "redirect:/";
	}

	// 입력값 정규화
	private static String n(String v) {
		return (v == null) ? "" : v.trim();
	}

	private static void validateLocalStep1(String email, String password, String passwordConfirm) {
		if (email.isEmpty() || !EMAIL_RULE.matcher(email).matches()) {
			throw new IllegalStateException("이메일 형식이 올바르지 않습니다.");
		}
		if (password.isEmpty()) {
			throw new IllegalStateException("비밀번호를 입력해 주세요.");
		}
		if (!PW_RULE.matcher(password).matches()) {
			throw new IllegalStateException("비밀번호는 8자리 이상, 영문/숫자를 포함하고 공백 없이 특수문자를 사용할 수 있습니다.");
		}
		if (!password.equals(passwordConfirm)) {
			throw new IllegalStateException("비밀번호가 일치하지 않습니다.");
		}
	}

	private void clearKakaoSignupIfExists(HttpSession session) {
		String kakaoKey = (String) session.getAttribute(SignupSessionKeys.KAKAO_SIGNUP_KEY);
		if (kakaoKey != null) {
			kakaoSignupStore.delete(kakaoKey);
			session.removeAttribute(SignupSessionKeys.KAKAO_SIGNUP_KEY);
		}
	}

	private static void cleanupSignupSession(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
	        try { session.invalidate(); } catch (IllegalStateException ignore) {}
	    }
	}
	
	private static boolean isAuthenticated(Authentication authentication) {
	    return authentication != null
	            && authentication.isAuthenticated()
	            && !(authentication instanceof AnonymousAuthenticationToken);
	}
}