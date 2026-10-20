// 내 정보 화면 스크립트(제이쿼리 미사용)
// - 내 정보 화면: 비밀번호 변경/회원탈퇴 모달 + 서버 연동 (fetch 사용)

(function () {
  const base =
    (typeof window.contextPath === 'string' ? window.contextPath :
      (typeof cpath === 'string' ? cpath : ''));

  // JSP에서 주입: const isKakao = true/false;
  const IS_KAKAO = (typeof isKakao === 'boolean') ? isKakao : false;

  const PW_RULE = /^(?=.*[A-Za-z])(?=.*\d)\S{8,}$/;

  function $(id) {
    return document.getElementById(id);
  }

  function show(el) { if (el) el.style.display = ''; }
  function hide(el) { if (el) el.style.display = 'none'; }

  function openModal(modalEl) {
    if (!modalEl) return;
    modalEl.classList.add('active');
  }
  function closeModal(modalEl) {
    if (!modalEl) return;
    modalEl.classList.remove('active');
  }

  async function postForm(url, dataObj) {
    const body = new URLSearchParams(dataObj || {});
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
      credentials: 'same-origin',
      body
    });

    const json = await res.json().catch(() => null);
    return { res, json };
  }

  // ===== 모달 제어(인라인 onclick도 지원) =====
  window.closePasswordModal = function () {
    closeModal($('passwordModal'));
  };
  window.closeDeleteModal = function () {
    closeModal($('deleteModal'));
  };

  function openPasswordModalLocal() {
    const msg = $('passwordModalMessage');
    const form = $('passwordForm');

    if (msg) { msg.textContent = ''; hide(msg); }
    if (form) show(form);

    openModal($('passwordModal'));
  }

  function openPasswordModalKakao() {
    const msg = $('passwordModalMessage');
    const form = $('passwordForm');

    if (msg) {
      msg.textContent = '카카오 계정은 카카오 사이트에서 비밀번호 변경을 진행하실 수 있습니다.';
      show(msg);
    }
    if (form) hide(form);

    openModal($('passwordModal'));
  }

  function openDeleteModal() {
    const group = $('deletePasswordGroup');
    const input = $('deletePassword');

    if (IS_KAKAO) {
      if (group) hide(group);
      if (input) input.value = '';
    } else {
      if (group) show(group);
      if (input) input.value = '';
    }

    openModal($('deleteModal'));
  }

  // ===== 이벤트 바인딩 =====
  document.addEventListener('DOMContentLoaded', function () {
    const btnPw = $('changePasswordBtn');
    const btnDel = $('deleteAccountBtn');
    const pwForm = $('passwordForm');
    const delForm = $('deleteForm');

    const passwordModal = $('passwordModal');
    const deleteModal = $('deleteModal');

    if (btnPw) {
      btnPw.addEventListener('click', function () {
        if (IS_KAKAO) openPasswordModalKakao();
        else openPasswordModalLocal();
      });
    }

    if (pwForm) {
      pwForm.addEventListener('submit', async function (e) {
        e.preventDefault();

        if (IS_KAKAO) {
          alert('카카오 계정은 카카오 사이트에서 비밀번호 변경을 진행하실 수 있습니다.');
          return;
        }

        const cur = ($('currentPassword')?.value || '').trim();
        const next = ($('newPassword')?.value || '').trim();
        const next2 = ($('confirmPassword')?.value || '').trim();

        if (!cur || !next || !next2) {
          alert('모든 항목을 입력해 주세요.');
          return;
        }
        if (next !== next2) {
          alert('새 비밀번호가 일치하지 않습니다.');
          return;
        }
        if (!PW_RULE.test(next)) {
          alert('비밀번호는 8자리 이상, 영문/숫자를 포함하고 공백 없이 특수문자를 사용할 수 있습니다.');
          return;
        }

        try {
          const { json } = await postForm(base + '/myinfo/password', {
            currentPassword: cur,
            newPassword: next,
            confirmPassword: next2
          });

          if (json && json.ok) {
            alert(json.message || '비밀번호가 변경되었습니다.');
            window.closePasswordModal();
            if ($('currentPassword')) $('currentPassword').value = '';
            if ($('newPassword')) $('newPassword').value = '';
            if ($('confirmPassword')) $('confirmPassword').value = '';
          } else {
            alert((json && json.message) ? json.message : '비밀번호 변경에 실패했습니다.');
          }
        } catch (err) {
          alert('비밀번호 변경 요청 중 오류가 발생했습니다.');
        }
      });
    }

    if (btnDel) {
      btnDel.addEventListener('click', function () {
        openDeleteModal();
      });
    }

    if (delForm) {
      delForm.addEventListener('submit', async function (e) {
        e.preventDefault();

        const msg = '정말 회원탈퇴 하시겠습니까?\n탈퇴 시 계정 및 연동된 데이터가 모두 삭제되며 복구할 수 없습니다.';
        if (!confirm(msg)) return;

        const pw = ($('deletePassword')?.value || '').trim();
        if (!IS_KAKAO && !pw) {
          alert('회원탈퇴를 위해 비밀번호를 입력해 주세요.');
          return;
        }

        try {
          const { json } = await postForm(base + '/myinfo/delete', IS_KAKAO ? {} : { password: pw });

          if (json && json.ok) {
            // AT 제거
            if (typeof window.clearAccessToken === 'function') {
              window.clearAccessToken();
            } else {
              try { sessionStorage.removeItem('AT'); } catch (_) {}
            }

            alert('회원탈퇴가 완료되었습니다.');
            const to = json.redirect ? (base + json.redirect) : (base + '/');
            window.location.href = to;
          } else {
            alert((json && json.message) ? json.message : '회원탈퇴에 실패했습니다.');
          }
        } catch (err) {
          alert('회원탈퇴 요청 중 오류가 발생했습니다.');
        }
      });
    }

    // 모달 바깥 클릭 시 닫기
    window.addEventListener('click', function (e) {
      if (passwordModal && e.target === passwordModal) window.closePasswordModal();
      if (deleteModal && e.target === deleteModal) window.closeDeleteModal();
    });
  });
})();
