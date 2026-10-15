/**
 * 화면 공통 유틸 (window.appUtil)
 *
 *  - 알림: toast / alert / confirm / prompt (SweetAlert2)
 *  - 서버 호출: ajaxPost (JSON) + 세션 만료(401) 공통 처리
 *  - 입력 검사, XSS 방어, 마크다운 렌더링, 저장소 래퍼, 출처 원문 정리
 */
const appUtil = window.appUtil || {};
window.appUtil = appUtil;

/* ==============================================================
   알림 (SweetAlert2). 다크 모드에 맞춰 배경/글자색을 바꾼다.
   ============================================================== */

function swalTheme() {
    const isDark = document.documentElement.classList.contains('dark');
    return {
        background: isDark ? '#222222' : '#f5f5f5',
        color: isDark ? '#ffffff' : '#333333'
    };
}

const appToast = Swal.mixin({
    toast: true,
    position: 'top-end',
    showConfirmButton: false,
    timer: 2500,
    timerProgressBar: true
});

/** 화면 우측 상단에 잠깐 뜨는 알림 */
appUtil.toast = function(message) {
    appToast.fire(Object.assign({ icon: 'success', title: message }, swalTheme()));
};

/**
 * 확인 버튼만 있는 알림 창. 첫 줄은 제목, 나머지 줄은 본문으로 표시한다.
 * title/text 모두 평문으로 처리되므로(HTML 파싱 안 함) 메시지에 태그가 섞여도 안전하다.
 *
 * @param {string} message 표시할 메시지 (\n 으로 줄바꿈)
 * @param {string} icon sweetalert2 아이콘 (error/warning/info/success)
 */
appUtil.alert = function(message, icon = 'warning') {
    const lines = String(message ?? '').split('\n');
    const title = lines.shift();
    const text = lines.join('\n').trim();

    return Swal.fire(Object.assign({
        title: title,
        text: text.length > 0 ? text : undefined,
        icon: icon,
        confirmButtonText: '확인',
        heightAuto: false,
        width: '400px'
    }, swalTheme()));
};

/** 확인/취소 창. 확인을 누르면 true */
appUtil.confirm = async function(message) {
    const result = await Swal.fire(Object.assign({
        title: message,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonText: '확인',
        cancelButtonText: '취소',
        heightAuto: false,
        width: '360px',
        confirmButtonColor: '#ef4444'
    }, swalTheme()));
    return result.isConfirmed;
};

/** 한 줄 입력 창. 취소하면 null */
appUtil.prompt = async function(titleMessage, defaultValue = '') {
    const result = await Swal.fire(Object.assign({
        title: titleMessage,
        input: 'text',
        inputValue: defaultValue,
        showCancelButton: true,
        confirmButtonText: '확인',
        cancelButtonText: '취소',
        heightAuto: false,
        inputValidator: (value) => {
            if (!value.trim()) {
                return '값을 비울 수 없습니다!';
            }
        }
    }, swalTheme()));
    return result.isConfirmed ? result.value : null;
};

/* ==============================================================
   서버 호출
   ============================================================== */

/**
 * JSON POST 요청.
 *
 * @param {string} url
 * @param {object} data 요청 본문 (JSON 직렬화)
 * @param {function(object)} success 성공 콜백 (응답 JSON)
 * @param {function(jqXHR)} error 실패 콜백. 401 은 공통 처리하므로 호출되지 않는다.
 */
appUtil.ajaxPost = function(url, data, success, error) {
    $.ajax(url, {
        type: 'POST',
        dataType: 'json',
        contentType: 'application/json',
        data: JSON.stringify(data ?? {}),
        timeout: 1000 * 60 * 3,
        cache: false,
        success: function(response) {
            if (typeof success === 'function') {
                success(response);
            }
        },
        error: function(xhr) {
            // 세션 만료(미인증): 개별 화면의 실패 토스트 대신 포털 이동 alert 하나만 띄운다.
            if (xhr.status === 401) {
                appUtil.handleSessionExpired();
                return;
            }
            if (typeof error === 'function') {
                error(xhr);
            }
        }
    });
};

/**
 * 세션 만료(서버 401) 처리. SSO 인증 실패 페이지(views/sso/fail.html)와 같은 방식으로
 * alert 을 띄우고, 확인을 누르면(또는 창을 닫으면) 포털 주소로 이동한다.
 *
 * 동시에 나간 여러 요청이 모두 401 을 받아도 alert 은 한 번만 띄운다.
 * 포털 주소는 chat.html 이 내려주는 SSO_PORTAL_URL(sso.portal-url) 을 쓴다.
 */
appUtil.sessionExpiredHandled = false;
appUtil.handleSessionExpired = function() {
    if (appUtil.sessionExpiredHandled) {
        return;
    }
    appUtil.sessionExpiredHandled = true;

    const portalUrl = (typeof SSO_PORTAL_URL === 'string' && SSO_PORTAL_URL) ? SSO_PORTAL_URL : '/sso';

    // replace 를 써서 뒤로가기로 만료된 화면에 돌아오지 않게 한다. (fail.html 과 동일)
    appUtil.alert('세션이 만료되었습니다.\n포털에서 다시 접속해 주세요.', 'error').then(function () {
        window.location.replace(portalUrl);
    });
};

/* ==============================================================
   입력 검사
   ============================================================== */

/** null/undefined 이거나 공백뿐인 문자열인지 */
appUtil.isEmptyString = function(s) {
    return s === null || s === undefined || String(s).trim().length === 0;
};

/** 공백을 모두 지운 뒤 빈 문자열인지. (null/undefined 는 false) */
appUtil.removeAllSpacesAndIsBlank = function(str) {
    if (str === undefined || str === null) {
        return false;
    }
    return String(str).replace(/\s+/g, '') === '';
};

/* ==============================================================
   XSS 방어 유틸
   - 화면에 innerHTML/템플릿 문자열로 꽂히는 모든 외부 유래 문자열
     (LLM 답변, 검색 출처 제목/본문, 사용자 질문, 대화방 제목)은
     반드시 아래 함수를 거쳐야 한다.
   ============================================================== */

const APP_HTML_ESCAPE_MAP = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
    '`': '&#96;'
};

/**
 * 텍스트 노드 위치에 넣을 문자열을 이스케이프한다.
 * @param {*} str
 * @returns {string}
 */
appUtil.escapeHtml = function(str) {
    if (str === null || str === undefined) {
        return '';
    }

    return String(str).replace(/[&<>"'`]/g, function(ch) {
        return APP_HTML_ESCAPE_MAP[ch];
    });
}

/**
 * HTML 속성값(따옴표 안) 위치에 넣을 문자열을 이스케이프한다.
 * 속성 탈출(" 로 속성을 닫고 이벤트 핸들러를 주입하는 공격)을 막는다.
 * @param {*} str
 * @returns {string}
 */
appUtil.escapeAttr = function(str) {
    return appUtil.escapeHtml(str);
}

// 입력 차단 문구 (서버 InputGuard.REJECT_MESSAGE 와 동일하게 유지)
appUtil.MARKUP_REJECT_MESSAGE = 'HTML 태그나 스크립트가 포함된 내용은 입력할 수 없습니다.';

/**
 * 사용자 입력에 HTML 태그나 스크립트 스킴이 포함되어 있는지 판정한다.
 *
 * 전송 전에 사용자에게 안내하기 위한 용도이며, 실제 차단은 서버(InputGuard.java)가 한다.
 * 판정 규칙은 서버와 맞춰 둔다.
 *  - '<' 바로 뒤에 영문자 / '/' / '!' / '?'  (<img, </div, <!--, <?xml)
 *    ('< 3억', '<5억', '<규정>' 은 태그가 아니므로 통과)
 *  - javascript: / vbscript: / livescript: / data:text/html
 *  - 판정 전 정규화: 전각(＜), HTML 엔티티(&lt; &#60; &#x3c;), URL 인코딩(%3C, 이중 인코딩)
 *
 * @param {*} str
 * @returns {boolean} 차단 대상이면 true
 */
appUtil.containsMarkup = function(str) {
    if (str === null || str === undefined) {
        return false;
    }

    const namedEntities = { lt: '<', gt: '>', colon: ':', sol: '/', tab: '\t', newline: '\n' };
    let s = String(str).normalize('NFKC');

    for (let i = 0; i < 3; i++) {
        const decoded = s
            .replace(/%([0-7][0-9a-fA-F])/g, function(m, hex) {
                return String.fromCharCode(parseInt(hex, 16));
            })
            .replace(/&#([xX]?)([0-9a-fA-F]{1,7});?/g, function(m, x, digits) {
                const cp = parseInt(digits, x ? 16 : 10);
                if (!x && /[a-fA-F]/.test(digits)) return m;   // 10진 엔티티에 a-f 가 섞이면 엔티티가 아님
                return (Number.isInteger(cp) && cp >= 0 && cp <= 0x10FFFF) ? String.fromCodePoint(cp) : m;
            })
            .replace(/&(lt|gt|colon|sol|tab|newline);?/gi, function(m, name) {
                return namedEntities[name.toLowerCase()];
            });

        if (decoded === s) break;
        s = decoded.normalize('NFKC');
    }

    if (/<[a-zA-Z\/!?]/.test(s)) {
        return true;
    }
    const compact = s.replace(/[\s\x00-\x1F\x7F]/g, '');
    return /(javascript|vbscript|livescript):|data:text\/html/i.test(compact);
}

// marked 로 변환한 HTML 중 허용할 태그/속성 (마크다운이 생성 가능한 범위로 제한)
const APP_MD_ALLOWED_TAGS = [
    'p', 'br', 'hr', 'span', 'div',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'strong', 'b', 'em', 'i', 'u', 's', 'del', 'ins', 'mark', 'sub', 'sup',
    'code', 'pre', 'blockquote',
    'ul', 'ol', 'li',
    'a', 'img',
    'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td', 'caption',
    'input' // 마크다운 체크리스트(- [x]) 용
];

const APP_MD_ALLOWED_ATTR = [
    'href', 'title', 'src', 'alt', 'width', 'height',
    'align', 'colspan', 'rowspan',
    'class', 'id',
    'type', 'checked', 'disabled'
];

/**
 * LLM 이 생성한 마크다운을 안전한 HTML 로 변환한다.
 *
 * marked 의 출력은 신뢰할 수 없다(모델 출력이나 RAG 원문에 <script>,
 * <img onerror=...> 등이 섞여 들어올 수 있음). 따라서 DOMPurify 로
 * 반드시 한 번 걸러낸 결과만 innerHTML 에 넣는다.
 *
 * 라이브러리가 없는 환경에서는 원문을 이스케이프한 평문으로 되돌린다.
 * (기능은 떨어지되, 절대 원본 HTML 을 그대로 흘리지 않는다)
 *
 * @param {string} markdownText
 * @returns {string} 삽입 가능한 HTML 문자열
 */
appUtil.renderMarkdown = function(markdownText) {
    if (this.isEmptyString(markdownText)) {
        return '';
    }

    // marked 미탑재: 평문으로 이스케이프 후 줄바꿈만 살림
    if (typeof marked === 'undefined') {
        return this.escapeHtml(markdownText).replace(/\n/g, '<br>');
    }

    const rawHtml = marked.parse(markdownText);

    // DOMPurify 미탑재: 변환 결과를 신뢰할 수 없으므로 평문 처리
    if (typeof DOMPurify === 'undefined') {
        console.error('[보안] DOMPurify 를 불러오지 못했습니다. 마크다운을 평문으로 표시합니다.');
        return this.escapeHtml(markdownText).replace(/\n/g, '<br>');
    }

    return DOMPurify.sanitize(rawHtml, {
        ALLOWED_TAGS: APP_MD_ALLOWED_TAGS,
        ALLOWED_ATTR: APP_MD_ALLOWED_ATTR,
        ALLOW_DATA_ATTR: false
    });
}

/**
 * 답변의 문서 번호 라벨을 한글 표준형 '[문서N]' 으로 통일한다.
 * 모델이 '[document1]', '[Doc 1]' 처럼 영어로 바꿔 쓰는 경우가 있다.
 * (서버 AnswerSections.normalizeCitationLabels 와 같은 규칙)
 *
 * 스트리밍 중 '[docu' 처럼 덜 도착한 라벨은 매칭되지 않고, 완성되는 시점에 치환된다.
 *
 * @param {string} text
 * @returns {string}
 */
appUtil.normalizeCitationLabels = function(text) {
    if (this.isEmptyString(text)) {
        return text;
    }
    return String(text).replace(/\[\s*(?:문서|document|doc)\s*[-_#]?\s*(\d{1,2})\s*\]/gi, '[문서$1]');
}

/**
 * 외부 링크가 부모 창을 조작하지 못하도록 처리 (tabnabbing 방어)
 * DOMPurify 훅은 라이브러리 로드 이후 한 번만 등록한다.
 */
appUtil.initSanitizerHooks = function() {
    if (typeof DOMPurify === 'undefined' || appUtil._sanitizerHookInstalled) {
        return;
    }

    DOMPurify.addHook('afterSanitizeAttributes', function(node) {
        if (node.tagName === 'A' && node.hasAttribute('href')) {
            node.setAttribute('target', '_blank');
            node.setAttribute('rel', 'noopener noreferrer');
        }
    });

    appUtil._sanitizerHookInstalled = true;
}

/* ==============================================================
   localStorage 안전 접근
   브라우저 정책(쿠키/저장소 차단, 시크릿 모드 제한, iframe 샌드박스)에 따라
   localStorage 접근 자체가 예외를 던질 수 있다. 테마/모델 선택 같은 부가 기능
   때문에 초기화 로직 전체가 멈추지 않도록 감싸서 사용한다.
   ============================================================== */

appUtil.storageGet = function(key, defaultValue = null) {
    try {
        const value = window.localStorage.getItem(key);
        return value === null ? defaultValue : value;
    } catch (e) {
        console.warn('[저장소] 읽기 실패, 기본값을 사용합니다:', key);
        return defaultValue;
    }
}

appUtil.storageSet = function(key, value) {
    try {
        window.localStorage.setItem(key, value);
        return true;
    } catch (e) {
        console.warn('[저장소] 쓰기 실패, 설정이 유지되지 않습니다:', key);
        return false;
    }
}

/* ==============================================================
   검색엔진 출처 원문 정리
   두 가지 문제를 함께 해결한다.
   1) 색인 데이터에 실제 개행 대신 "\n" 두 글자(백슬래시+n)가 들어있는 경우가 있어
      화면에 백슬래시가 그대로 노출된다.
   2) HTML 은 개행을 공백으로 취급하므로, 원문을 그냥 꽂으면 조문 구조가 사라지고
      한 줄로 길게 이어져 읽기 어렵다.
   ============================================================== */

/**
 * 출처 원문을 화면 표시용으로 정규화한다. (개행 구조 유지)
 * 우측 상세 패널·검색결과 발췌처럼 줄 구분을 살려야 하는 곳에서 사용한다.
 * @param {*} text
 * @returns {string} 개행이 실제 \n 문자로 정리된 평문
 */
appUtil.normalizeSourceText = function(text) {
    if (this.isEmptyString(text)) {
        return '';
    }

    return String(text)
        // 1) 리터럴 이스케이프(백슬래시 + 문자 두 글자)를 실제 제어문자로 복원.
        //    색인 데이터에 개행이 "\n" 두 글자로 저장된 경우를 처리한다.
        .replace(/\\r\\n/g, '\n')
        .replace(/\\n/g, '\n')
        .replace(/\\r/g, '\n')
        .replace(/\\t/g, ' ')
        .replace(/\\u000[adAD]/g, '\n')
        // 2) 실제 개행 문자 통일 (CRLF, CR -> LF)
        .replace(/\r\n?/g, '\n')
        // 3) 줄 단위로 가로 공백 압축 및 양끝 정리
        .split('\n')
        .map(function (line) { return line.replace(/[ \t\u00a0]+/g, ' ').trim(); })
        .join('\n')
        // 4) 연속된 빈 줄은 하나로 축약
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}

/**
 * 출처 원문을 한 줄 미리보기용으로 축약한다. (개행을 공백으로 흡수)
 * 가로 스크롤 카드처럼 2~3줄만 보여주는 좁은 영역에서 사용한다.
 * @param {*} text
 * @returns {string}
 */
appUtil.toPreviewText = function(text) {
    return this.normalizeSourceText(text).replace(/\s+/g, ' ').trim();
}
