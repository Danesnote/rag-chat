// [방어코드] Lucide(아이콘 라이브러리)가 CDN 차단/미로딩된 환경에서도
// lucide.createIcons() 호출이 에러로 스크립트를 멈추지 않도록 no-op으로 대체한다.
// (아이콘은 표시되지 않지만 나머지 기능은 정상 동작한다. 로컬 파일 포함 시 이 블록은 무시됨)
if (typeof window.lucide === 'undefined') {
    console.warn('[UI] Lucide 아이콘 라이브러리를 불러오지 못했습니다. 아이콘 없이 동작합니다.');
    window.lucide = { createIcons: function () {} };
}

let counselQuestions = [];      //좌측 메뉴 채팅 히스토리
let counselDisplayLimit = 3;  //좌측 메뉴 채팅 히스토리 최초 보여줄 개수
let counselNextPage = 0;
let counselHasNext = false;
let isLoadingCounselPage = false;
let counselRequestVersion = 0;

let archivedQuestions = [];     //좌측 메뉴 아카이브 채팅 히스토리
let currentSearchTab = 'counsel';   //좌측 검색 뷰의 현재 탭 (counsel/archive)
let archiveDisplayLimit = 3;  //좌측 메뉴 아카이브 최초 보여줄 개수
let archiveNextPage = 0;
let archiveHasNext = false;
let isLoadingArchivePage = false;
let archiveRequestVersion = 0;

const CHAT_LIST_PAGE_SIZE = 10;

let isAnimatingCounselLoad = false;     //대화 애니메이션 로딩
let isAnimatingArchiveLoad = false;     //아카이브 대화 애니메이션 로딩

let isGenerating = false;               //생성중 표기
let abortController = null;             // 통신을 중간에 끊기 위한 컨트롤러
let activeStreamSeq = null;             // 현재 스트리밍 중인 채팅 블록의 seq (중단 시 UI 정리에 사용)

let isCounselOpen = true;
let isArchiveOpen = true;

//사이드바오픈
let isSidebarOpen = true;
let isSidebarAutoCollapsed = false;     // 창 폭이 좁아서 자동으로 접힌 상태인지 (사용자 조작과 구분)

let currentChatSeq = chatSeq; // 전역 변수: 현재 방 번호 기억

/* ==============================================================
   테마(다크/라이트)
   chat.html 의 인라인 스크립트가 화면 그려지기 전에 먼저 적용하고,
   JS 로드 후 applySavedTheme() 가 저장값 기준으로 한 번 더 확정한다.
   저장 키는 localStorage 의 'theme' 이며 값은 'dark' | 'light' 뿐이다.
   ============================================================== */

// 저장된 테마를 화면에 적용한다. 저장값이 없으면 라이트가 기본이다.
const applySavedTheme = function() {
    const saved = appUtil.storageGet('theme', null);
    const isDark = (saved === 'dark');

    $('html').toggleClass('dark', isDark);
    $('#theme-text').text(isDark ? 'DARK MODE' : 'LIGHT MODE');
}

// 테마를 바꾸고 저장한다.
const setTheme = function(theme) {
    const isDark = (theme === 'dark');

    $('html').toggleClass('dark', isDark);
    $('#theme-text').text(isDark ? 'DARK MODE' : 'LIGHT MODE');
    appUtil.storageSet('theme', isDark ? 'dark' : 'light');
}

// 검색 뷰를 나타내는 쿼리스트링 값 (/chat/main/123?view=search)
const SEARCH_VIEW_PARAM = 'search';

//추천질문
const recommendedQuestions = [
    "연차휴가는 어떻게 산정되며, 미사용 연차는 어떻게 처리되나요?",
    "국내 출장 시 여비 지급 기준과 정산 절차는 어떻게 되나요?",
    "정보보안 위반 시 징계 기준과 처리 절차는 무엇인가요?",
    "재택근무 신청 요건과 승인 절차는 어떻게 되나요?",
    "법인카드 사용 시 지출증빙은 어떻게 작성·제출하나요?"
];

//최초 로딩
jQuery(window).ready(function(){
    const init = function (){
        appUtil.initSanitizerHooks();   // 마크다운 새니타이저 훅 등록 (링크 tabnabbing 방어)

        lucide.createIcons();
        getLeftChatHistory();       //좌측 메뉴 채팅 히스토리 가져오기
        getLeftArchiveHistory();    //좌측 메뉴 아카이브 채팅 히스토리 가져오기

        // Initial Render
        renderRecommended();

        //테마
        // 깜빡임 방지용 인라인 스크립트(chat.html)가 이미 클래스를 붙였겠지만,
        // 템플릿 사본이 달라 그 스크립트가 옛 버전일 수 있으므로 저장값 기준으로 한 번 더 확정한다.
        applySavedTheme();

        // 모델 선택 UI 제거됨(모델 단일). 별도 초기화 불필요.

        //chatseq가 url에 있으면 특정 대화내역 가져오기
        if(chatSeq != null && chatSeq > 0){
            loadChatHistory(chatSeq);
        }

        window.history.replaceState({ chatSeq: chatSeq }, '', window.location.href);

        // ?view=search 로 진입/새로고침한 경우 검색 뷰를 복원한다. (히스토리는 그대로 둔다)
        if (isSearchViewUrl()) {
            openSearchView('none');
        }
    };

    //추천검색어에서 검색하기
    $(document).on('click', '.search-recommend-trigger', function() {
        if (isGenerating) {
            return; // 생성 중에는 추천 질문 클릭을 무시 (중복 요청 방지)
        }

        let val = $(this).data('query');
        if(val !== undefined && val !== null && val !== ''){
            streamAnswer(val);
        }else{
            return;
        }

        $('#btn-close-bottom-recommended').trigger('click');
    });

    // 추천 질문 키보드 접근 (Enter / Space)
    $(document).on('keydown', '.search-recommend-trigger', function(e) {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            $(this).trigger('click');
        }
    });

    //대화내역 전체 히스토리 가져오기
    $(document).on('click', '.search-trigger', function(e) {
        e.stopPropagation();
        let id = $(this).data('id');
        openChatHistory(id);
    });

    //질문 전송
    $('.btn-search').on('click', function() {
        if (isGenerating) {
            stopGenerating();
        } else {
            let val = $(this).closest('.relative').find('input, textarea').val();
            streamAnswer(val);
        }
    });

    //질문전송
    // Enter: 전송 / Shift+Enter: 줄바꿈(기본 동작을 막지 않는다)
    $('#main-search-input, #bottom-search-input').on('keydown', function(e) {
        if (e.key !== 'Enter' || e.shiftKey) {
            return;
        }

        // 한글 입력 조합 중의 Enter는 조합 확정이므로 전송하지 않는다.
        if (e.originalEvent && e.originalEvent.isComposing) {
            return;
        }

        e.preventDefault();   // 줄바꿈이 들어가지 않도록 막는다

        if (isGenerating) {
            return;
        }

        // 공백만 입력하고 Enter를 친 경우 아무 동작도 하지 않는다.
        if (appUtil.removeAllSpacesAndIsBlank($(this).val())) {
            return;
        }

        streamAnswer($(this).val());
    });

    // 입력 내용에 맞춰 높이를 조절한다. (Shift+Enter로 늘어난 줄이 보이도록)
    $('#main-search-input, #bottom-search-input').on('input', function() {
        autoGrowInput(this);
    });

    //아카이브 보관
    $(document).on('click', '.action-archive', function(e) {
        e.stopPropagation();
        let id = $(this).data('id');
        if(id <= 0){
            appUtil.toast('해당 데이터를 찾을 수 없습니다. 새로고침 후 다시 시도해 주세요.');
            return;
        }

        let param = {};
        param.id = id;
        param.action = 'add';

        appUtil.ajaxPost('/chat/api/chat_action', param,function (result){
            if(result.result){
                let itemIndex = counselQuestions.findIndex(q => Number(q.id) === Number(id));
                if (itemIndex > -1) {
                    let item = counselQuestions[itemIndex];
                    counselQuestions.splice(itemIndex, 1);
                    archivedQuestions.push(item);
                    sortChatRoomItemsByRegDateDesc(archivedQuestions);
                    renderCharHistoryAll();
                    getLeftChatHistory(true);
                    getLeftArchiveHistory(true);
                    appUtil.toast('아카이브 보관이 완료되었습니다.');
                }
            }else{
                appUtil.toast(result.message);
            }
        }, function(e){
            appUtil.toast('아카이브 보관에 실패하였습니다.');
        });
    });

    //아카이브 보관 취소
    $(document).on('click', '.action-unarchive', function(e) {
        e.stopPropagation();
        let id = $(this).data('id');
        if(id <= 0){
            appUtil.toast('해당 데이터를 찾을 수 없습니다. 새로고침 후 다시 시도해 주세요.');
            return;
        }

        let param = {};
        param.id = id;
        param.action = 'cancel';

        appUtil.ajaxPost('/chat/api/chat_action', param,function (result){
            if(result.result){
                let itemIndex = archivedQuestions.findIndex(q => Number(q.id) === Number(id));
                if (itemIndex > -1) {
                    let item = archivedQuestions[itemIndex];
                    archivedQuestions.splice(itemIndex, 1);
                    counselQuestions.push(item);
                    sortChatRoomItemsByRegDateDesc(counselQuestions);
                    renderCharHistoryAll();
                    getLeftChatHistory(true);
                    getLeftArchiveHistory(true);
                    // 성공 모달
                    appUtil.toast('아카이브 보관이 취소되었습니다.')
                }
            }else{
                appUtil.toast(result.message);
            }
        }, function(e){
            appUtil.toast('아카이브 보관 취소에 실패하였습니다.');
        });
    });

    //채팅방, 아카이브 이름변경
    $(document).on('click', '.action-rename', async function(e) {
        e.stopPropagation();
        let id = $(this).data('id');
        let type = $(this).data('type');

        if(id <= 0){
            appUtil.toast('해당 데이터를 찾을 수 없습니다. 새로고침 후 다시 시도해 주세요.');
            return;
        }

        let targetArr = type === 'counsel' ? counselQuestions : archivedQuestions;
        let itemIndex = targetArr.findIndex(q => Number(q.id) === Number(id));

        if (itemIndex === -1) {
            appUtil.toast('해당 데이터를 찾을 수 없습니다. 새로고침 후 다시 시도해 주세요.');
            return;
        }

        const newName = await appUtil.prompt("새로운 이름을 입력하세요:", targetArr[itemIndex].text);

        if (newName !== null && !appUtil.removeAllSpacesAndIsBlank(newName)) {
            if (appUtil.containsMarkup(newName)) {
                appUtil.toast(appUtil.MARKUP_REJECT_MESSAGE);
                return;
            }

            let param = {};
            param.id = id;
            param.action = 'update';
            param.title = newName;

            appUtil.ajaxPost('/chat/api/chat_action', param,function (result){
                if(result.result){
                    targetArr[itemIndex].text = newName;
                    appUtil.toast('이름변경이 완료되었습니다.');
                    renderCharHistoryAll();
                }else{
                    appUtil.toast(result.message);
                }
            }, function (e){
                appUtil.toast('이름변경에 실패하였습니다.');
            });
        }
    });

    //대화내역 삭제
    $(document).on('click', '.action-delete', async function(e) {
        e.stopPropagation();
        let confirm = await appUtil.confirm("해당 대화내역을 삭제하시겠습니까?");
        if(confirm){
            let id = $(this).data('id');
            let type = $(this).data('type');

            if(id <= 0){
                appUtil.toast('해당 데이터를 찾을 수 없습니다. 새로고침 후 다시 시도해 주세요.');
                return;
            }

            let param = {};
            param.id = id;
            param.action = 'delete';

            appUtil.ajaxPost('/chat/api/chat_action', param,function (result){
                if(result.result){
                    appUtil.toast('대화내역 삭제가 완료되었습니다.');

                    if (type === 'counsel') {

                        counselQuestions = counselQuestions.filter(q => Number(q.id) !== Number(id));
                    } else {
                        archivedQuestions = archivedQuestions.filter(q => Number(q.id) !== Number(id));
                    }
                    renderCharHistoryAll();

                    if (type === 'counsel') {
                        getLeftChatHistory(true);
                    } else {
                        getLeftArchiveHistory(true);
                    }

                    //현재 대화내역과 같은 방을 삭제하면 초기화화면으로 넘겨준다.
                    if(chatSeq !== null && chatSeq > 0 && Number(param.id) === Number(chatSeq)){
                        showNewChatView('replace');
                    }
                }else{
                    appUtil.toast(result.message);
                }
            }, function(e){
                appUtil.toast('아카이브 삭제에 실패하였습니다');
            });
        }
    });

    // 대화영역 숨기기 / 보이기
    $('#toggle-counsel').on('click', function() {
        isCounselOpen = !isCounselOpen;
        if(isCounselOpen) {
            $('#counsel-wrapper').slideDown(200);
        } else {
            $('#counsel-wrapper').slideUp(200);
        }
        $(this).find('.toggle-icon').replaceWith(`<i data-lucide="${isCounselOpen ? 'chevron-up' : 'chevron-down'}" class="w-4 h-4 toggle-icon"></i>`);
        lucide.createIcons();
    });

    //아카이브 대화 영역 숨기기 / 보이기
    $('#toggle-archive').on('click', function() {
        isArchiveOpen = !isArchiveOpen;
        if(isArchiveOpen) {
            $('#archive-wrapper').slideDown(200);
        } else {
            $('#archive-wrapper').slideUp(200);
        }
        $(this).find('.toggle-icon').replaceWith(`<i data-lucide="${isArchiveOpen ? 'chevron-up' : 'chevron-down'}" class="w-4 h-4 toggle-icon"></i>`);
        lucide.createIcons();
    });

    //좌측 채팅 내역 더보기
    $(document).on('click', '#toggle-counsel-list', function(e) {
        e.stopPropagation();
        if (counselQuestions.length > counselDisplayLimit) {
            counselDisplayLimit = Math.min(counselDisplayLimit + CHAT_LIST_PAGE_SIZE, counselQuestions.length);
            isAnimatingCounselLoad = true;
            $('#counsel-section-container').removeClass('shrink-0 flex-1').addClass('shrink');
            renderLeftCharHistory();
            isAnimatingCounselLoad = false;
        } else if (counselHasNext) {
            isAnimatingCounselLoad = true;
            $('#counsel-section-container').removeClass('shrink-0 flex-1').addClass('shrink');
            getLeftChatHistory(false, true);
        } else {
            counselDisplayLimit = 3;
            $('#counsel-section-container').removeClass('flex-1').addClass('shrink-0');
            renderLeftCharHistory();
        }
    });

    //좌측 아카이브 내역 더보기
    $(document).on('click', '#toggle-archive-list', function(e) {
        e.stopPropagation();
        if (archivedQuestions.length > archiveDisplayLimit) {
            archiveDisplayLimit = Math.min(archiveDisplayLimit + CHAT_LIST_PAGE_SIZE, archivedQuestions.length);
            isAnimatingArchiveLoad = true;
            renderLeftArchiveHistory();
            isAnimatingArchiveLoad = false;
        } else if (archiveHasNext) {
            isAnimatingArchiveLoad = true;
            getLeftArchiveHistory(false, true);
        } else {
            archiveDisplayLimit = 3;
            renderLeftArchiveHistory();
        }
    });

    // ----------------------------------------------------
    // UI Interactions & Event Listeners
    // ----------------------------------------------------

    // --- THEME TOGGLE LOGIC ---
    //다크모드 토글
    $('#theme-toggle').on('click', function() {
        $('html').toggleClass('dark');
        setTheme($('html').hasClass('dark') ? 'dark' : 'light');
    });

    //좌측 사이드 바 토글 클릭
    $('#toggle-sidebar').on('click', function() {
        isSidebarOpen = !isSidebarOpen;
        isSidebarAutoCollapsed = false; // 사용자가 직접 조작했으므로 자동 접힘 상태 해제
        updateSidebarUI();
    });

    // 축소 상태의 대화/아카이브 아이콘 클릭 시 사이드바 확장 + 해당 섹션으로 스크롤
    $('.sidebar-expand-trigger').on('click', function() {
        if (!isSidebarOpen) {
            isSidebarOpen = true;
            isSidebarAutoCollapsed = false;
            updateSidebarUI();
        }
        const targetId = $(this).data('target');
        if (targetId) {
            const $target = $('#' + targetId);
            if ($target.length && $target[0].scrollIntoView) {
                $target[0].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }
        }
    });

    //좌측 사이드바 리사이즈 (좁은 화면에서 자동 접기)
    // 사용자가 직접 접어둔 사이드바를 리사이즈가 임의로 다시 펼치지 않도록,
    // "자동으로 접힌 경우"에만 되돌린다.
    $(window).resize(function() {
        const isNarrow = $(window).width() < 1024;

        if (isNarrow && isSidebarOpen) {
            isSidebarOpen = false;
            isSidebarAutoCollapsed = true;
            updateSidebarUI();
        } else if (!isNarrow && !isSidebarOpen && isSidebarAutoCollapsed) {
            isSidebarOpen = true;
            isSidebarAutoCollapsed = false;
            updateSidebarUI();
        }
    });
    // Trigger resize on load
    $(window).trigger('resize');


    // --- NEW: 하단 추천 팝업 로직 ---
    $('#btn-bottom-menu').on('click', function(e) {
        e.stopPropagation();
        const $popup = $('#bottom-recommended-popup');
        if ($popup.hasClass('hidden')) {
            $popup.removeClass('hidden').addClass('flex animate-in fade-in zoom-in-95 duration-200');
        } else {
            $popup.addClass('hidden').removeClass('flex animate-in fade-in zoom-in-95 duration-200');
        }
    });

    $('#btn-close-bottom-recommended').on('click', function(e) {
        e.stopPropagation();
        $('#bottom-recommended-popup').addClass('hidden').removeClass('flex');
    });

    // 새대화(home 버튼)
    $(document).on('click', '.btn-home', function() {
        if (!isSidebarOpen) {
            isSidebarOpen = true;
            updateSidebarUI();

            setTimeout(() => {
                showNewChatView();
            }, 300);
            return;
        }

        showNewChatView();
    });

    // 브라우저 뒤로가기/앞으로가기 시 현재 URL에 맞는 화면만 갱신
    $(window).on('popstate', function() {
        const pathChatSeq = getChatSeqFromPath();

        // 검색 뷰(?view=search)가 먼저다. 경로에 방 번호가 남아 있어도 화면은 검색 뷰여야 한다.
        //
        // 경로의 대화를 여기서 미리 불러오지 않는다. loadChatHistory 는 비동기라
        // openSearchView() 가 블록을 숨긴 뒤에 응답이 도착해 대화가 검색 뷰 아래에
        // 딸려 나온다. 그 대화는 한 번 더 뒤로가기(=/chat/main/{seq})로 갈 때 불러온다.
        if (isSearchViewUrl()) {
            openSearchView('none');
            return;
        }

        if (pathChatSeq !== null) {
            openChatHistory(pathChatSeq, 'none');
        } else {
            showNewChatView('none');
        }
    });

    $('.btn-clear').on('click', function() {
        const $input = $(this).closest('.relative').find('input, textarea');
        $input.val('').focus();
        $input.each(function() { this.style.height = ''; });   // 늘어난 높이도 되돌린다
    });

    // 4. 답변, 검색결과 탭 클릭 Tabs Switching for Result View (동적으로 생성된 탭 지원)
    $(document).on('click', '.chat-tab-btn', function() {
        // 1. 내가 누른 버튼의 고유 seq와 target(answer/search) 가져오기
        let seq = $(this).data('seq');
        let target = $(this).data('target');

        // 2. [스타일 변경] 같은 블록(seq) 안에 있는 탭들을 전부 비활성화 색상으로 바꿈
        $(`.chat-tab-btn[data-seq="${seq}"]`)
            .removeClass('bg-gray-200 text-gray-900 dark:bg-[#5a5a5a] dark:text-gray-200')
            .addClass('text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300');

        // 3. [스타일 변경] 방금 내가 클릭한 버튼만 활성화 색상으로 바꿈
        $(this)
            .addClass('bg-gray-200 text-gray-900 dark:bg-[#5a5a5a] dark:text-gray-200')
            .removeClass('text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300');

        // 4. [컨텐츠 전환] target에 따라 해당 seq의 답변/검색 영역을 껐다 켬
        if (target === 'answer') {
            $(`#content-answer-${seq}`).show();
            $(`#content-search-${seq}`).hide();
        } else {
            $(`#content-search-${seq}`).show();
            $(`#content-answer-${seq}`).hide();

            // 숨겨진 동안에는 높이가 0으로 측정되므로, 표시된 뒤에 발췌 잘림을 다시 판정한다.
            markClampedExcerpts($(`#content-search-${seq}`));
        }

        // 5. 우측 패널 닫기 및 스크롤 이벤트 트리거 (기존 유지)
        $('#btn-close-panel').click();
        setTimeout(() => $('#main-scroll-area').trigger('scroll'), 10);
    });

    // (모델 드롭다운 제거됨: 모델이 단일이라 선택 UI 미사용)

    // 검색 방식 토글(dev 전용): select 가 렌더된 경우에만 저장값 복원/저장.
    //  - 운영(prod)에서는 select 가 렌더되지 않으므로 getRetrievalMode()가 hidden 기본값을 사용한다.
    const $retrievalSelect = $('#retrieval-mode-select');
    if ($retrievalSelect.length) {
        const savedRetrievalMode = appUtil.storageGet('retrievalMode');
        $retrievalSelect.val(savedRetrievalMode || $('#retrieval-mode-default').val() || '2');
        $retrievalSelect.on('change', function() {
            appUtil.storageSet('retrievalMode', $(this).val());
        });
    }

    // ===== 좌측 검색 =====
    //검색 버튼 클릭 → 검색 뷰 열기
    $('#btn-sidebar-search, #btn-sidebar-search-icon').on('click', function() {
        openSearchView();
    });
    //검색 뷰 - 대화 탭
    $('#search-tab-counsel').on('click', function() {
        currentSearchTab = 'counsel';
        $(this).addClass('bg-gray-200 text-gray-900 dark:bg-[#5a5a5a] dark:text-gray-200').removeClass('text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300');
        $('#search-tab-archive').removeClass('bg-gray-200 text-gray-900 dark:bg-[#5a5a5a] dark:text-gray-200').addClass('text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300');
        renderSearchListView($('#search-page-input').val());
    });
    //검색 뷰 - 아카이브 탭
    $('#search-tab-archive').on('click', function() {
        currentSearchTab = 'archive';
        $(this).addClass('bg-gray-200 text-gray-900 dark:bg-[#5a5a5a] dark:text-gray-200').removeClass('text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300');
        $('#search-tab-counsel').removeClass('bg-gray-200 text-gray-900 dark:bg-[#5a5a5a] dark:text-gray-200').addClass('text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300');
        renderSearchListView($('#search-page-input').val());
    });
    //검색창 입력 시 실시간 필터
    $('#search-page-input').on('keyup', function() {
        renderSearchListView($(this).val());
    });
    //검색창 지우기
    $('#search-page-clear').on('click', function() {
        $('#search-page-input').val('').focus();
        renderSearchListView();
    });

    // 6. Context Menu (Z-index 층 꼬임 해결)
    $(document).on('click', '.menu-btn', function(e) {
        e.stopPropagation();
        let id = $(this).data('id');
        let type = $(this).data('type');
        let targetMenu = $(`#menu-${type}-${id}`);

        $('.popup-menu').not(targetMenu).addClass('hidden');
        $('.menu-btn').removeClass('opacity-100 bg-gray-300 dark:bg-white/10 text-gray-900 dark:text-gray-200').addClass('opacity-0 text-gray-400 dark:text-gray-600');

        // 방금 클릭한 메뉴가 속한 부모 리스트만 화면 최상단으로 끄집어 올립니다!
        $('#counsel-list > div, #archive-list > div').css('z-index', 'auto');

        if (targetMenu.hasClass('hidden')) {
            let $item = $(this).closest('.relative');

            // ★ 등장 애니메이션(animation-fill-mode: both)이 남긴 transform 은
            //    position:fixed 팝업의 기준 좌표계를 바꿔 메뉴를 엉뚱한 위치에 띄운다.
            //    좌표를 재기 전에 먼저 제거한다.
            clearItemEnterAnimation($item);

            // 클릭한 부모 z-index 9999로 설정
            $item.css('z-index', '9999');

            $(this).removeClass('opacity-0 text-gray-400 dark:text-gray-600 hover:bg-gray-300 dark:hover:bg-white/10').addClass('opacity-100 bg-gray-300 dark:bg-white/10 text-gray-900 dark:text-gray-200');
            targetMenu.removeClass('hidden');

            let btnRect = this.getBoundingClientRect();
            let menuHeight = targetMenu.outerHeight() || 110;
            let topPos = btnRect.bottom + 4;

            if (topPos + menuHeight > $(window).height()) {
                topPos = btnRect.top - menuHeight - 4;
            }

            targetMenu.css({ top: topPos + 'px', left: (btnRect.right - 150) + 'px' });
        } else {
            targetMenu.addClass('hidden');
        }
    });

    // 등장 애니메이션이 끝나면 남아 있는 transform 을 즉시 제거한다.
    // (transform 이 남아 있으면 그 안의 position:fixed 팝업 메뉴 좌표가 어긋난다)
    $(document).on('animationend', '#counsel-list > div, #archive-list > div', function(e) {
        if (e.target !== this) return;
        clearItemEnterAnimation($(this));
    });

    // 스크롤 시 팝업 닫고 z-index 초기화
    // ※ scroll 이벤트는 버블링되지 않는다. 실제로 스크롤되는 요소는
    //    #sidebar-lists 가 아니라 #counsel-wrapper / #archive-wrapper 이므로,
    //    캡처 단계로 받아서 어떤 요소가 스크롤되든 전부 잡는다.
    document.addEventListener('scroll', function() {
        $('.popup-menu').addClass('hidden');
        $('.menu-btn').removeClass('opacity-100 bg-gray-300 dark:bg-white/10 text-gray-900 dark:text-gray-200').addClass('opacity-0 text-gray-400 dark:text-gray-600 hover:bg-gray-300 dark:hover:bg-white/10');

        // ★ 스크롤 시 초기화
        $('#counsel-list > div, #archive-list > div').css('z-index', 'auto');
    }, true);

    // 창 크기가 바뀌면 계산해 둔 좌표가 어긋나므로 팝업을 닫는다.
    $(window).on('resize', function() {
        $('.popup-menu').addClass('hidden');
        $('.menu-btn').removeClass('opacity-100 bg-gray-300 dark:bg-white/10 text-gray-900 dark:text-gray-200').addClass('opacity-0 text-gray-400 dark:text-gray-600 hover:bg-gray-300 dark:hover:bg-white/10');
        $('#counsel-list > div, #archive-list > div').css('z-index', 'auto');
    });

    // 잘린 제목에 마우스를 올리면 전체 제목을 툴팁으로 보여준다.
    $(document).on('mouseenter', '[data-tooltip]', function() {
        const el = this;
        if (tooltipShowTimer) clearTimeout(tooltipShowTimer);
        tooltipShowTimer = setTimeout(function() { showTruncatedTooltip(el); }, 120);
    });
    $(document).on('mouseleave', '[data-tooltip]', hideTruncatedTooltip);
    $(document).on('click', hideTruncatedTooltip);
    document.addEventListener('scroll', hideTruncatedTooltip, true);
    $(window).on('resize', hideTruncatedTooltip);

    // 바탕화면 클릭 시 팝업 닫고 z-index 초기화
    $(document).on('click', function(e) {
        $('.popup-menu').addClass('hidden');
        $('.menu-btn').removeClass('opacity-100 bg-gray-300 dark:bg-white/10 text-gray-900 dark:text-gray-200').addClass('opacity-0 text-gray-400 dark:text-gray-600 hover:bg-gray-300 dark:hover:bg-white/10');

        // ★ 바탕화면 클릭 시 초기화
        $('#counsel-list > div, #archive-list > div').css('z-index', 'auto');

        if (!$(e.target).closest('#bottom-recommended-popup, #btn-bottom-menu').length) {
            $('#bottom-recommended-popup').addClass('hidden').removeClass('flex');
        }
    });

    $(document).on('click', '.popup-menu', function(e) {
        e.stopPropagation();
    });



    // --- LLM 답변 복사 기능 ---
    $(document).on('click', '.btn-copy-answer', function() {
        const $btn = $(this);

        // 버튼을 감싸는 .action-bar-area의 바로 앞 형제(.ai-answer-block)를 찾고,
        // 그 안에 있는 id가 llm-content- 로 시작하는 요소를 찾습니다.
        let $contentDiv = $btn.closest('.action-bar-area').prev('.ai-answer-block').find('[id^="llm-content-"]');

        // 만약 ai-answer-block 이라는 클래스명이 아니더라도, 무조건 바로 앞 박스를 뒤지도록 하는 방어 코드
        if ($contentDiv.length === 0) {
            $contentDiv = $btn.closest('.action-bar-area').prev().find('[id^="llm-content-"]');
        }

        // HTML 속성이 아닌 메모리(.data)에서 원본 꺼내기!
        let textToCopy = '';
        if ($contentDiv.length > 0) {
            // 제이쿼리 메모리에 저장해둔 마크다운을 꺼내고, 혹시 없으면(에러 등) 화면의 텍스트라도 긁어옵니다.
            textToCopy = $contentDiv.data('markdown') || $contentDiv[0].innerText;
        }

        /*// 마크다운이 적용된 깔끔한 텍스트만 추출
        const textToCopy = $contentDiv.length > 0 ? $contentDiv[0].innerText : '';*/

        if (!textToCopy) {
            console.warn("복사할 내용이 없습니다.");
            return;
        }

        // ★ 성공 시 UI를 변경하는 공통 함수
        const showSuccessUI = () => {
            const originalHtml = $btn.html();
            $btn.html(`
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="w-3.5 h-3.5 text-green-500"><polyline points="20 6 9 17 4 12"></polyline></svg>
            <span class="font-medium tracking-wide text-green-600 dark:text-green-400">완료</span>
        `);
            setTimeout(() => { $btn.html(originalHtml); }, 2000);
        };

        // ★ 2. 환경에 따라 복사 방식 분기 (핵심!)
        if (navigator.clipboard && window.isSecureContext) {
            // [HTTPS 또는 localhost 환경] 최신 클립보드 API 사용
            navigator.clipboard.writeText(textToCopy)
                .then(showSuccessUI)
                .catch(err => {
                    console.error('클립보드 복사 실패:', err);
                    alert('복사에 실패했습니다.');
                });
        } else {
            // [HTTP (IP 접속) 환경] 옛날 방식(textarea 우회) 사용
            const textArea = document.createElement("textarea");
            textArea.value = textToCopy;

            // 화면에 안 보이게 숨기기
            textArea.style.position = "absolute";
            textArea.style.left = "-999999px";
            document.body.appendChild(textArea);

            textArea.select();

            try {
                document.execCommand('copy');
                showSuccessUI(); // 복사 성공 시 UI 변경
            } catch (err) {
                console.error('구버전 클립보드 복사 실패:', err);
                alert('이 브라우저에서는 복사 기능을 지원하지 않습니다.');
            } finally {
                textArea.remove(); // 쓴 다음엔 쓰레기통으로!
            }
        }
    });

    //우측 패널 --- RIGHT SIDE PANEL LOGIC ---
    function openSidePanel(id, contentText, titleText, version, effectiveDate, originalDocId) {
        // 제목과 본문이 둘 다 비어 있을 때만 열지 않는다. (한쪽이라도 내용이 있으면 표시)
        if(appUtil.removeAllSpacesAndIsBlank(contentText) && appUtil.removeAllSpacesAndIsBlank(titleText))
            return;

        // 출처 원문은 평문으로만 취급한다. (.html() 을 쓰면 원문 속 태그가 실행됨)
        // 정리되지 않은 원문이 넘어와도 안전하도록 여기서 한 번 더 정규화한다.
        $('#panel-title').text(titleText);
        $('#panel-content').text(appUtil.normalizeSourceText(contentText));

        // 메타데이터(문서번호·버전·시행일자) 표시
        let metaParts = [];
        if (id) metaParts.push('문서번호 ' + id);
        if (version) metaParts.push('버전 ' + version);
        if (effectiveDate) metaParts.push('시행일자 ' + effectiveDate);
        $('#panel-meta').text(metaParts.join('   ·   '));

        // 원문 사이트 바로가기. 원문 URL 설정이 없거나 originalDocId 가 없는 출처에서는 링크를 감춘다.
        const $externalLink = $('#panel-external-link');
        // jQuery .data() 는 "2172" 같은 값을 Number 로 바꿔서 돌려주므로 반드시 문자열로 감싼다.
        const seq = String(originalDocId ?? '').trim();
        if (SOURCE_VIEW_URL_PREFIX !== '' && seq !== '') {
            $externalLink.attr('href', SOURCE_VIEW_URL_PREFIX + encodeURIComponent(seq)).removeClass('hidden');
        } else {
            $externalLink.attr('href', '#').addClass('hidden');
        }

        $('#right-side-panel').removeClass('hidden').addClass('flex');
    }

    // 바로가기: 새 탭이 아니라 팝업창으로 띄운다.
    $('#panel-external-link').on('click', function(e) {
        e.preventDefault();
        const url = $(this).attr('href');
        if (!url || url === '#') {
            return;
        }
        const width = Math.min(1200, Math.max(900, Math.floor($(window).width() * 0.8)));
        const height = Math.min(900, Math.max(600, Math.floor($(window).height() * 0.9)));
        const left = window.screenX + Math.max(0, Math.floor((window.outerWidth - width) / 2));
        const top = window.screenY + Math.max(0, Math.floor((window.outerHeight - height) / 2));

        const popup = window.open(
            url,
            'sourceFullView',
            `width=${width},height=${height},left=${left},top=${top},resizable=yes,scrollbars=yes`
        );

        if (popup) {
            // tabnabbing 방어. 교차 출처 창이라 접근이 막힐 수 있으므로 감싼다.
            try { popup.opener = null; } catch (err) { /* 무시 */ }
            popup.focus();
        } else {
            appUtil.toast('팝업이 차단되었습니다. 브라우저의 팝업 차단을 해제해 주세요.');
        }
    });

    // 우측 패널 닫기 버튼
    $('#btn-close-panel').on('click', function() {
        $('#right-side-panel').addClass('hidden').removeClass('flex').css('width', '400px'); // 크기 초기화
    });

    // 창크기 복구 버튼 (새로 추가)
    $('#btn-reset-panel-size').on('click', function() {
        $rightPanel.addClass('transition-all duration-300').css('width', '400px');
        setTimeout(() => {
            $rightPanel.removeClass('transition-all').addClass('transition-colors');
        }, 300);
    });

    // 1. 답변 탭에서의 출처 박스
    $(document).on('click', '.source-card-box', function(e) {
        e.stopPropagation();
        const $box = $(this);
        const id = $box.data('source-id');
        const version = $box.data('version');
        const effectiveDate = $box.data('effective-date');

        // 화면에는 축약본만 보이므로, 패널에는 메모리에 보관한 전문을 넘긴다.
        const contentText = $box.data('fullContent') || $box.find('p').text();
        const titleText = $box.data('sourceTitle') || $box.find('.source-card-title').text();
        const originalDocId = $box.data('original-doc-id');

        openSidePanel(id, contentText, titleText, version, effectiveDate, originalDocId);
    });

    // 2. 검색결과 탭에서의 본문 클릭 이벤트
    $(document).on('click', '.search-result-box', function(e) {
        e.stopPropagation();
        const $box = $(this).closest('.search-result-box');
        const id = $box.data('source-id');
        const version = $box.data('version');
        const effectiveDate = $box.data('effective-date');

        // 발췌는 잘려 있을 수 있으므로, 패널에는 메모리에 보관한 전문을 넘긴다.
        const contentText = $box.data('fullContent') || $box.find('.search-body-text').text();
        const titleText = $box.data('sourceTitle') || $box.find('h3').text();
        const originalDocId = $box.data('original-doc-id');

        openSidePanel(id, contentText, titleText, version, effectiveDate, originalDocId);
    });

    // --- PANEL RESIZER LOGIC ---
    let isResizing = false;
    let $rightPanel = $('#right-side-panel');

    const startResize = function(e) {
        isResizing = true;
        $('body').css('cursor', 'col-resize').css('user-select', 'none');
        // 부드러운 트랜지션 임시 제거 (빠른 마우스 따라가기)
        $rightPanel.removeClass('transition-colors duration-300');
        e.preventDefault();
    };

    const moveResize = function(clientX) {
        if (!isResizing) return;

        let bodyPaddingRight = parseInt($('body').css('padding-right')) || 16;
        let newWidth = window.innerWidth - bodyPaddingRight - clientX;

        if (newWidth < 300) newWidth = 300;
        let maxWidth = Math.min(window.innerWidth * 0.45, 600);
        if (newWidth > maxWidth) newWidth = maxWidth;

        $rightPanel.css('width', newWidth + 'px');
    };

    const endResize = function() {
        if (isResizing) {
            isResizing = false;
            $('body').css('cursor', 'default').css('user-select', 'auto');
            $rightPanel.addClass('transition-colors duration-300');
        }
    };

    // 마우스 + 터치(태블릿) 양쪽 지원
    $('#panel-resizer').on('mousedown', startResize);
    $('#panel-resizer').on('touchstart', startResize);

    $(document).on('mousemove', function(e) {
        moveResize(e.clientX);
    });
    $(document).on('touchmove', function(e) {
        if (!isResizing) return;
        const touch = e.originalEvent.touches[0];
        if (touch) {
            moveResize(touch.clientX);
        }
    });

    $(document).on('mouseup', endResize);
    $(document).on('touchend touchcancel', endResize);

    // 맨 밑으로 내리는 버튼
    const $scrollArea = $('#main-scroll-area');
    $scrollArea.on('scroll', function() {
        if ($('div[id^="view-result"]').is(':visible')) {
            const isScrollable = this.scrollHeight > this.clientHeight;
            const isScrolledUp = (this.scrollHeight - this.scrollTop - this.clientHeight) > 100;
            if (isScrollable && isScrolledUp) {
                $('#scroll-indicator').removeClass('hidden');
            } else {
                $('#scroll-indicator').addClass('hidden');
            }
        }
        // 스트리밍 중 사용자가 바닥 근처로 다시 내려오면 추종을 재개한다.
        if (isGenerating && !streamAutoFollow && isNearBottom(this)) {
            startAutoFollow();
        }
    });

    // 스트리밍 중 사용자가 위로 스크롤(휠 위쪽 / 터치)하면 추종을 멈추고 그 자리에 둔다.
    // (프로그램이 만든 스크롤은 wheel/touch 이벤트를 발생시키지 않으므로 사용자 조작만 감지된다.)
    $scrollArea.on('wheel', function(e) {
        if (isGenerating && e.originalEvent && e.originalEvent.deltaY < 0) {
            stopAutoFollow();
        }
    });
    $scrollArea.on('touchmove', function() {
        if (isGenerating) {
            stopAutoFollow();
        }
    });

    //맨밑으로 내리는 버튼 클릭시 화면 가장아래로 스크롤 (스트리밍 중이면 추종도 재개)
    $("#scroll-indicator").on("click", function() {
        scrollChatToBottom(true);
        if (isGenerating) {
            startAutoFollow();
        }
    });

    init();
});


const updateBrowserHistory = function(url, chatId, historyAction) {
    if (historyAction === 'none') {
        return;
    }

    const state = { chatSeq: chatId };
    if (historyAction === 'replace') {
        window.history.replaceState(state, '', url);
    } else if (getCurrentUrlPath() !== url) {
        // 검색 뷰는 같은 경로에 ?view=search 만 붙으므로 쿼리스트링까지 포함해 비교해야 한다.
        window.history.pushState(state, '', url);
    }
}

// 현재 주소의 경로 + 쿼리스트링 (히스토리 중복 push 판정에 사용)
const getCurrentUrlPath = function() {
    return window.location.pathname + window.location.search;
}

const getChatSeqFromPath = function() {
    const match = window.location.pathname.match(/^\/chat\/main\/(\d+)\/?$/);
    return match ? Number(match[1]) : null;
}

// 현재 주소가 검색 뷰인지 (?view=search)
const isSearchViewUrl = function() {
    return new URLSearchParams(window.location.search).get('view') === SEARCH_VIEW_PARAM;
}

/**
 * 검색 뷰 열기.
 *
 * 검색 뷰도 브라우저 히스토리에 한 칸을 차지해야 뒤로가기가 직관적으로 동작한다.
 * 경로는 그대로 두고 ?view=search 만 붙이므로,
 * '채팅방 → 검색탭'에서 뒤로가기를 하면 보고 있던 채팅방으로 돌아온다.
 *
 * @param {string} historyAction 'push'(기본) | 'replace' | 'none'
 */
const openSearchView = function(historyAction = 'push') {
    if (!isSidebarOpen) {
        isSidebarOpen = true;
        updateSidebarUI();
    }

    $('#view-initial, div[id^="view-result"]').hide();
    $('#bottom-input-area, #scroll-indicator').addClass('hidden');
    $('#btn-close-panel').click();
    $('#view-search').show();
    $('#search-page-input').val('').focus();
    renderSearchListView();

    updateBrowserHistory(
        window.location.pathname + '?view=' + SEARCH_VIEW_PARAM,
        getChatSeqFromPath(),
        historyAction
    );
}

const clearChatContent = function() {
    $('#view-area').empty();
    $('#main-scroll-area > .chat-turn-block').remove();
}

const showNewChatView = function(historyAction = 'push') {
    if (isGenerating) {
        stopGenerating();
    }

    chatSeq = null;
    currentChatSeq = null;
    activeStreamSeq = null;

    updateBrowserHistory('/chat/main', null, historyAction);

    clearChatContent();
    $('#view-search').hide();
    $('#view-initial').show();
    $('#bottom-input-area, #scroll-indicator, #bottom-recommended-popup').addClass('hidden');
    resetInputs();
    $('#main-scroll-area').scrollTop(0);
    $('#right-side-panel').addClass('hidden').removeClass('flex');

    renderCharHistoryAll();
    lucide.createIcons();
}

const openChatHistory = function(id, historyAction = 'push') {
    const targetChatSeq = Number(id);
    if (!Number.isInteger(targetChatSeq) || targetChatSeq <= 0) {
        appUtil.toast('해당 대화이력을 찾을 수 없습니다.');
        return;
    }

    if (isGenerating) {
        stopGenerating();
    }

    // 검색 뷰에서 대화를 클릭한 경우: 검색 뷰를 닫고 채팅 영역을 표시
    $('#view-search, #view-initial').hide();
    // openSearchView() 가 숨겨 둔 대화 블록을 다시 보여준다.
    // 이미 로드된 대화(isAlreadyLoaded)는 loadChatHistory() 를 타지 않으므로
    // 여기서 풀어주지 않으면 display:none 인 채로 남아 빈 화면이 된다.
    $('div[id^="view-result"]').show();
    $('#bottom-input-area').removeClass('hidden');

    const isAlreadyLoaded = currentChatSeq === targetChatSeq && $('#view-area').children().length > 0;

    chatSeq = targetChatSeq;
    currentChatSeq = targetChatSeq;
    updateBrowserHistory('/chat/main/' + targetChatSeq, targetChatSeq, historyAction);

    renderCharHistoryAll();
    focusActiveChatInSidebar();

    if (!isAlreadyLoaded) {
        loadChatHistory(targetChatSeq);
    }
}

const loadChatHistory = function(requestedChatSeq) {
    // 1. 껍데기 세팅
    $('#view-search').hide();
    $('#view-initial').hide();
    $('#bottom-input-area').removeClass('hidden');
    clearChatContent();

    // 2. 백엔드로 데이터 요청 (GET, POST 등 구현하신 방식에 맞게!)
    appUtil.ajaxPost('/chat/api/history', { chatSeq: requestedChatSeq }, function(res) {
        if (currentChatSeq !== requestedChatSeq) {
            return;
        }

        if (res.result) {
            // [{id: 912, role: "USER", content: "설명"}, {id: 913, role: "ASSISTANT", content: "##..."}]
            const historyList = res.data;

            if (historyList && historyList.length > 0) {

                // ★ 짝짓기를 위한 임시 변수
                let currentQuestion = "";
                let currentBlockSeq = null;

                historyList.forEach(item => {
                    if (item.role === 'USER') {
                        // 1) USER를 만나면 일단 질문 내용과 ID를 기억해 둡니다.
                        currentQuestion = item.content;
                        currentBlockSeq = item.id; // 이 질문의 ID를 전체 블록의 고유키로 씁니다.
                    }
                    else if (item.role === 'ASSISTANT') {
                        // 2) ASSISTANT를 만났을 때 비로소 화면에 블록을 그립니다!

                        // 방어코드: 혹시 에러로 USER 데이터 없이 ASSISTANT만 있을 경우
                        if (currentBlockSeq === null) {
                            currentBlockSeq = item.id;
                            currentQuestion = "(질문 내역 없음)";
                        }


                        // (A) 아까 저장해둔 질문(currentQuestion)으로 껍데기를 만들어 붙임
                        $('#view-area').append(createChatBlockHtml(currentBlockSeq, currentQuestion));

                        // 뱅글뱅글 도는 로딩창 끄고, 결과창(답변 영역) 열어주기
                        $(`#source-loading-state-${currentBlockSeq}`).removeClass('flex flex-col animate-pulse').addClass('hidden');
                        $(`#answer-loading-state-${currentBlockSeq}`).removeClass('flex flex-col').addClass('hidden');

                        // result-state 였지만, 지금은 answer-result-state 입니다!
                        $(`#answer-result-state-${currentBlockSeq}`).removeClass('hidden');

                        if(item.searchContent !== null && item.searchContent !== "" && item.searchContent !== undefined){
                            $(`#source-card-container-${currentBlockSeq}`).removeClass('hidden');

                            createSourceBlockHtml(item.searchContent, currentBlockSeq);
                            //console.log(item.searchContent);
                        }

                        // (C) 방금 만난 ASSISTANT의 답변 텍스트 꽂아넣기 (XSS 필터 통과 후 삽입)
                        // 이번 수정 전에 저장된 답변에는 '[document1]' 같은 영어 라벨이 남아 있을 수 있어 표시 전에 통일한다.
                        const answerMarkdown = appUtil.normalizeCitationLabels(item.content);
                        $(`#llm-content-${currentBlockSeq}`).html(appUtil.renderMarkdown(answerMarkdown));

                        // 마크다운 원본도 속성에 저장
                        $(`#llm-content-${currentBlockSeq}`).data('markdown', answerMarkdown);


                        // 과거 대화 내역에도 복사 버튼을 붙여준다.
                        $(`#llm-content-${currentBlockSeq}`).closest('.ai-answer-block').after(createCopyButtonHtml());

                        // 3) 다음 짝꿍을 찾기 위해 임시 변수 초기화
                        currentBlockSeq = null;
                        currentQuestion = "";


                    }
                });

                // ★ 방어코드: 마지막에 USER가 질문만 던지고 에러가 나서 ASSISTANT 답변이 없는 경우
                if (currentBlockSeq !== null) {
                    $('#view-area').append(createChatBlockHtml(currentBlockSeq, currentQuestion));
                    $(`#source-loading-state-${currentBlockSeq}`).addClass('hidden').removeClass('flex');
                    $(`#answer-loading-state-${currentBlockSeq}`).addClass('hidden').removeClass('flex');
                    $(`#answer-result-state-${currentBlockSeq}`).removeClass('hidden');
                    $(`#llm-content-${currentBlockSeq}`).html('<span class="text-red-500 font-bold">답변 생성 중 오류가 발생했거나 중단된 대화입니다.</span>');
                }

                lucide.createIcons();

                // 응답이 늦게 도착하는 사이에 검색 뷰가 열렸다면, 새로 그린 블록도 숨긴다.
                // (검색 뷰 아래에 대화가 딸려 나와 스크롤이 생기는 것을 막는다)
                if ($('#view-search').is(':visible')) {
                    $('div[id^="view-result"]').hide();
                    return;
                }

                // 스크롤 맨 아래로
                setTimeout(() => scrollChatToBottom(), 50);

            } else {
                appUtil.toast("해당 대화이력은 삭제되었거나, 존재하지 않습니다.");
                showNewChatView('replace');
                //$('#main-scroll-area').append('<div class="text-center py-10 text-gray-500">대화 내역이 없습니다.</div>');
            }
        } else {
            appUtil.toast("대화 내역을 불러오는데 실패했습니다.");
            showNewChatView('replace');
        }
    }, function() {
        if (currentChatSeq !== requestedChatSeq) {
            return;
        }

        appUtil.toast("대화 내역을 불러오는데 실패했습니다.");
        showNewChatView('replace');
    });
}

/**
 * textarea 높이를 내용에 맞춰 조절한다.
 * CSS의 min-height/max-height 안에서만 늘어나고, 한계를 넘으면 내부 스크롤이 생긴다.
 *
 * @param {HTMLElement} el 대상 textarea
 */
const autoGrowInput = function(el) {
    if (!el) {
        return;
    }

    // 값이 비면 인라인 높이를 지워 CSS 기본 높이로 되돌린다.
    // 빈 textarea 의 scrollHeight 는 placeholder 를 기준으로 계산되므로,
    // 화면이 좁아 placeholder 가 줄바꿈되면 빈 입력창이 여러 줄 높이로 남는다.
    if (el.value === '') {
        el.style.height = '';
        return;
    }

    // 먼저 auto로 되돌려야 줄어드는 경우(내용 삭제)에도 정확히 계산된다.
    el.style.height = 'auto';
    el.style.height = el.scrollHeight + 'px';
}

/**
 * 입력창을 비우고 높이를 원래대로 되돌린다.
 */
const resetInputs = function() {
    $('#main-search-input, #bottom-search-input').val('').each(function() {
        this.style.height = '';   // 인라인 높이를 제거해 CSS 기본값(min-height)으로 복귀
    });
}

/**
 * 채팅 영역을 맨 아래로 스크롤한다.
 * @param {boolean} animate true 면 부드럽게, false 면 즉시 이동
 * @param {number} duration 애니메이션 시간(ms)
 */
const scrollChatToBottom = function(animate = false, duration = 400) {
    const $scrollArea = $('#main-scroll-area');
    if ($scrollArea.length === 0) {
        return;
    }

    const bottom = $scrollArea[0].scrollHeight;
    if (animate) {
        $scrollArea.stop(true).animate({ scrollTop: bottom }, duration);
    } else {
        // 즉시 이동(확). #main-scroll-area 에서 CSS scroll-smooth 를 제거했으므로 이대로 순간이동한다.
        $scrollArea[0].scrollTop = bottom;
    }
}

// =====================================================================
// 스트리밍 중 "부드러운 바닥 고정" (smooth stick-to-bottom)
//  - 답변이 나오는 동안 바닥을 부드럽게 추종해 글자가 스르륵 위로 올라가는 느낌을 준다.
//  - 사용자가 위로 스크롤(휠/터치)하면 추종을 멈추고 그 자리에 둔다(읽기 방해 X).
//  - 다시 바닥 근처로 오면(또는 "맨 아래로" 버튼) 추종을 재개한다.
// =====================================================================
let streamAutoFollow = false;        // true면 바닥을 부드럽게 추종
let followRAFId = null;              // requestAnimationFrame 핸들
const FOLLOW_NEAR_BOTTOM_PX = 120;  // 이 거리 이내면 '바닥 근처'로 간주

const isNearBottom = function(el, threshold = FOLLOW_NEAR_BOTTOM_PX) {
    if (!el) return false;
    return (el.scrollHeight - el.scrollTop - el.clientHeight) <= threshold;
};

// 한 프레임씩 남은 거리의 일부만 이동 → ease-out 느낌으로 바닥에 붙는다.
const tickFollow = function() {
    const el = document.getElementById('main-scroll-area');
    if (!el || !streamAutoFollow) { followRAFId = null; return; }
    const dist = el.scrollHeight - el.scrollTop - el.clientHeight;
    if (dist <= 1) { followRAFId = null; return; }
    // 남은 거리의 35%씩(최소 6px) 이동. 부드러움은 이 rAF 루프가 담당한다.
    // (#main-scroll-area 에서 CSS scroll-smooth 를 제거해 프레임 이동은 즉시 반영된다.)
    el.scrollTop += Math.max(6, dist * 0.35);
    followRAFId = requestAnimationFrame(tickFollow);
};

// 새 글자가 렌더될 때마다 호출: 추종 중이면 이징 루프를 (필요 시) 다시 돌린다.
const requestFollow = function() {
    if (streamAutoFollow && followRAFId === null) {
        followRAFId = requestAnimationFrame(tickFollow);
    }
};

// 추종 시작(스트리밍 시작 시)
const startAutoFollow = function() {
    streamAutoFollow = true;
    requestFollow();
};

// 추종 정지(사용자가 위로 이동 → 그 자리에 둔다)
const stopAutoFollow = function() {
    streamAutoFollow = false;
    if (followRAFId !== null) {
        cancelAnimationFrame(followRAFId);
        followRAFId = null;
    }
};

// 원문 전문 보기 URL. 뒤에 해당 출처의 originalDocId 를 붙인다.
// chat.html 이 rag.ui.source-view-url 값을 window.SOURCE_VIEW_URL 로 내려주며, 비어 있으면 바로가기를 감춘다.
const SOURCE_VIEW_URL_PREFIX = String(window.SOURCE_VIEW_URL || '').trim();

// 목록에 보여줄 제목 글자수 제한. DB 에는 원문 전체가 저장되고, 화면에서만 줄여 보여준다.
const TITLE_DISPLAY_LIMIT = 20;

// 제목을 표시용으로 자른다. (자른 경우에만 말줄임표를 붙인다)
const truncateTitle = function(text) {
    const t = String(text ?? '');
    return t.length > TITLE_DISPLAY_LIMIT ? t.slice(0, TITLE_DISPLAY_LIMIT) + '...' : t;
}

// ==============================================================
// 커스텀 툴팁 : 잘린(truncate) 제목에 마우스를 올리면 전체 제목을 보여준다.
//  - data-tooltip 속성이 있고, 실제로 말줄임 처리된 요소에만 표시한다.
//  - body 에 #app-tooltip 하나를 만들어 재사용한다(position: fixed).
// ==============================================================
let $appTooltip = null;
let tooltipShowTimer = null;

const getAppTooltip = function() {
    if (!$appTooltip || !$appTooltip.parent().length) {
        $appTooltip = $('<div id="app-tooltip" role="tooltip" aria-hidden="true"></div>').appendTo('body');
    }
    return $appTooltip;
}

// 텍스트가 실제로 잘려 있는지 판단 (안 잘렸으면 툴팁을 띄우지 않는다)
const isTextTruncated = function(el) {
    return !!el && el.scrollWidth > el.clientWidth + 1;
}

// 툴팁을 띄울 필요가 있는지 판단한다.
//  - 글자수로 잘린 경우(화면 텍스트 != 원문) 또는 CSS 말줄임(...)이 걸린 경우
const shouldShowTooltip = function(el, fullText) {
    const shown = String(el.textContent || '').trim();
    return String(fullText).trim() !== shown || isTextTruncated(el);
}

const showTruncatedTooltip = function(el) {
    const text = $(el).attr('data-tooltip');
    if (!text || !shouldShowTooltip(el, text)) return;

    const $tip = getAppTooltip();
    // 크기를 재기 위해 화면 밖에서 먼저 그린다.
    $tip.text(text).attr('aria-hidden', 'false')
        .css({ top: '-9999px', left: '-9999px' })
        .addClass('is-visible');

    const rect = el.getBoundingClientRect();
    const tipW = $tip.outerWidth();
    const tipH = $tip.outerHeight();
    const gap = 6;
    const margin = 8;

    let top = rect.bottom + gap;
    if (top + tipH > $(window).height() - margin) {
        top = rect.top - tipH - gap;    // 아래로 넘치면 위쪽에 띄운다
    }
    if (top < margin) top = margin;

    let left = rect.left;
    if (left + tipW > $(window).width() - margin) {
        left = $(window).width() - tipW - margin;
    }
    if (left < margin) left = margin;

    $tip.css({ top: top + 'px', left: left + 'px' });
}

const hideTruncatedTooltip = function() {
    if (tooltipShowTimer) {
        clearTimeout(tooltipShowTimer);
        tooltipShowTimer = null;
    }
    if ($appTooltip) {
        $appTooltip.removeClass('is-visible').attr('aria-hidden', 'true');
    }
}

/**
 * 좌측 목록 항목의 "등장 애니메이션" 잔재(transform)를 제거한다.
 *
 * 더보기로 추가된 항목에는 animate-in / slide-in-from-top-8 클래스와
 * animation-fill-mode: both 가 붙는다. 애니메이션이 끝나도 fill-mode 때문에
 * 마지막 키프레임의 transform(translateY(0))이 계속 적용된 상태로 남는데,
 * transform 이 none 이 아닌 요소는 내부 position:fixed 요소의 기준 좌표계가 되어
 * 팝업 메뉴의 top/left 가 뷰포트가 아닌 항목 기준으로 계산되어 버린다.
 */
function clearItemEnterAnimation($item) {
    if (!$item || !$item.length) return;
    $item.removeClass('animate-in fade-in slide-in-from-top-8 slide-in-from-top-2 duration-700')
         .css({ 'animation': '', 'animation-delay': '', 'animation-fill-mode': '' });
}

/**
 * 답변 생성 중단 (중지 버튼, 새 대화 이동, 다른 대화방 열기 시 호출)
 */
function stopGenerating() {
    // 서버와의 통신을 실제로 끊는다.
    if (abortController) {
        abortController.abort();
        abortController = null; // 끊은 후에는 초기화
    }

    isGenerating = false;
    $('.icon-stop').addClass('hidden');
    $('.icon-send').removeClass('hidden');

    // 진행 중이던 블록의 로딩 UI 정리.
    // 각 블록의 ID 에는 seq 접미사가 붙으므로 접미사 없는 셀렉터로는 아무것도 잡히지 않는다.
    if (activeStreamSeq !== null) {
        $(`#source-loading-state-${activeStreamSeq}`).addClass('hidden').removeClass('flex flex-col animate-pulse');
        $(`#answer-loading-state-${activeStreamSeq}`).addClass('hidden').removeClass('flex');
        $(`#answer-result-state-${activeStreamSeq}`).removeClass('hidden');
    }

    // DOM 이 갱신될 짧은 시간을 벌어준 후 스크롤 이동
    setTimeout(() => scrollChatToBottom(true, 800), 50);
}

/**
 * 좌측 히스토리 메뉴 다시 그리기(script only)
 */
const renderCharHistoryAll = function(){
    renderLeftCharHistory();
    renderLeftArchiveHistory();
    // 검색 뷰가 열려 있으면 검색 결과도 함께 갱신 (아카이브/이름변경/삭제 반영)
    if ($('#view-search').is(':visible')) {
        renderSearchListView($('#search-page-input').val());
    }
}

// 검색 뷰가 화면에 떠 있을 때만 결과 목록을 다시 그린다.
// (목록 ajax 응답이 검색 뷰를 연 뒤에 도착하는 경우를 위한 갱신)
const refreshSearchListIfVisible = function() {
    if ($('#view-search').is(':visible')) {
        renderSearchListView($('#search-page-input').val());
    }
}

//검색 뷰 그리기 - 대화/아카이브 목록을 제목으로 클라이언트 필터링
const renderSearchListView = function(query = '') {
    $('#search-results-list').empty();
    let targetArr = currentSearchTab === 'counsel' ? counselQuestions : archivedQuestions;
    if (!Array.isArray(targetArr)) targetArr = [];

    let filtered = targetArr;
    if (query && query.trim() !== '') {
        const q = query.toLowerCase();
        filtered = targetArr.filter(item => String(item.text || '').toLowerCase().includes(q));
    }

    if (filtered.length === 0) {
        $('#search-results-list').append('<div class="py-8 text-center text-gray-500 text-[14px]">결과가 없습니다.</div>');
    } else {
        filtered.forEach(item => {
            const safeText = appUtil.escapeHtml(item.text);                       // 전체 제목(툴팁용)
            const safeShortText = appUtil.escapeHtml(truncateTitle(item.text));    // 표시용(20자)
            const dateStr = String(item.modDate || '');
            const datePart = dateStr.split(' ')[0] || '';
            const timePart = dateStr.split(' ')[1] || '';
            const archiveBtn = currentSearchTab === 'counsel'
                ? `<button class="hover:text-gray-700 dark:hover:text-gray-300 transition-colors action-archive" data-id="${item.id}" title="아카이브 보관"><i data-lucide="archive" class="w-[15px] h-[15px]"></i></button>`
                : `<button class="hover:text-gray-700 dark:hover:text-gray-300 transition-colors action-unarchive" data-id="${item.id}" title="아카이브 보관 취소"><i data-lucide="archive-restore" class="w-[15px] h-[15px]"></i></button>`;
            const removeBtn = currentSearchTab === 'counsel'
            ? `<button class="hover:text-red-500 dark:hover:text-red-400 transition-colors action-delete" data-id="${item.id}" data-type="${currentSearchTab}" title="삭제"><i data-lucide="trash-2" class="w-[15px] h-[15px]"></i></button>`
            : ``;
            $('#search-results-list').append(`
              <div class="search-result-row flex items-center justify-between py-2.5 border-b border-gray-200 dark:border-[#3a3a3a] group transition-colors">
                <div class="flex-1 pr-2 min-w-0">
                  <h3 class="text-[14px] font-bold text-gray-800 dark:text-gray-200 cursor-pointer group-hover:underline search-trigger transition-colors truncate" data-id="${item.id}" data-query="${safeText}" data-tooltip="${safeText}">${safeShortText}</h3>
                </div>
                <div class="flex items-center gap-8 shrink-0">
                  <div class="text-[11px] text-gray-500 text-center leading-[1.3] font-medium tracking-wide whitespace-nowrap">
                    ${datePart}<br/>${timePart}
                  </div>
                  <div class="flex items-center gap-4 text-gray-400 dark:text-gray-500">
                    ${archiveBtn}
                    <button class="hover:text-gray-700 dark:hover:text-gray-300 transition-colors action-rename" data-id="${item.id}" data-type="${currentSearchTab}" title="이름변경"><i data-lucide="arrow-left-right" class="w-[15px] h-[15px]"></i></button>
                    ${removeBtn}
                    </div>
                </div>
              </div>
            `);
        });
    }
    if (window.lucide) lucide.createIcons();
}

// 현재 검색 방식 값 반환.
//  - dev: 상단 select 값 사용
//  - prod(select 미렌더): hidden #retrieval-mode-default(서버 기본값) 사용, 최종 폴백 2(청크 확장)
const getRetrievalMode = function() {
    const $sel = $('#retrieval-mode-select');
    if ($sel.length) {
        const v = Number($sel.val());
        if (v) return v;
    }
    const d = Number($('#retrieval-mode-default').val());
    return d || 2;
}

const sortChatRoomItemsByRegDateDesc = function(items) {
    return items.sort((a, b) => {
        const modDateCompare = String(b.modDate ?? '').localeCompare(String(a.modDate ?? ''));
        return modDateCompare !== 0 ? modDateCompare : Number(b.id) - Number(a.id);
    });
}

// 현재 시각을 백엔드 modDate 포맷(yyyy-MM-dd HH:mm)으로 생성
const nowModDateString = function() {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// 특정 대화방을 좌측 목록 맨 위로 즉시 올린다 (서버 새로고침 없이, 생성형 AI처럼)
const promoteChatToTop = function(chatId) {
    const id = Number(chatId);
    if (!Number.isInteger(id) || id <= 0) {
        return;
    }
    const stamp = nowModDateString();

    // 대화 목록에서 찾기
    let item = counselQuestions.find(q => Number(q.id) === id);
    if (item) {
        item.modDate = stamp;
        sortChatRoomItemsByRegDateDesc(counselQuestions);
        renderLeftCharHistory();
        focusActiveChatInSidebar();
        return;
    }
    // 아카이브 목록에서 찾기
    item = archivedQuestions.find(q => Number(q.id) === id);
    if (item) {
        item.modDate = stamp;
        sortChatRoomItemsByRegDateDesc(archivedQuestions);
        renderLeftArchiveHistory();
        focusActiveChatInSidebar();
    }
}

const mergeChatRoomItems = function(currentItems, newItems) {
    const itemMap = new Map(currentItems.map(item => [item.id, item]));
    newItems.forEach(item => itemMap.set(item.id, item));
    return sortChatRoomItemsByRegDateDesc(Array.from(itemMap.values()));
}

/**
 * 좌측 메뉴 채팅 히스토리 가져오기
 */
const getLeftChatHistory = function(reset = true, expandAfterLoad = false){
    if (!reset && (isLoadingCounselPage || !counselHasNext)) {
        return;
    }

    if (reset) {
        counselRequestVersion += 1;
    }

    const requestVersion = counselRequestVersion;
    const requestedPage = reset ? 0 : counselNextPage;
    isLoadingCounselPage = true;

    appUtil.ajaxPost('/chat/api/left_chat_history', {
        page: requestedPage,
        size: CHAT_LIST_PAGE_SIZE
    }, function (result){
        if (requestVersion !== counselRequestVersion) {
            return;
        }

        const newItems = Array.isArray(result.content) ? result.content : [];
        counselQuestions = reset
            ? sortChatRoomItemsByRegDateDesc(newItems)
            : mergeChatRoomItems(counselQuestions, newItems);
        counselNextPage = Number(result.page ?? requestedPage) + 1;
        counselHasNext = result.hasNext === true;

        if (reset) {
            counselDisplayLimit = 3;
        } else if (expandAfterLoad) {
            counselDisplayLimit = Math.min(
                counselDisplayLimit + CHAT_LIST_PAGE_SIZE,
                counselQuestions.length
            );
        }

        if(chatSeq !== null && chatSeq > 0){
            let itemIndex = counselQuestions.findIndex(q => Number(q.id) === Number(chatSeq));
            if (itemIndex > 2) {
                counselDisplayLimit = Math.max(counselDisplayLimit, itemIndex + 1);
            }
        }

        isLoadingCounselPage = false;
        renderLeftCharHistory();
        // 검색 뷰가 열려 있으면(?view=search 로 새로고침 등) 목록이 도착한 뒤 다시 그린다.
        // 안 그러면 응답 전에 그려진 '결과가 없습니다' 가 그대로 남는다.
        refreshSearchListIfVisible();
        focusActiveChatInSidebar();
        isAnimatingCounselLoad = false;
    }, function (e){
        if (requestVersion !== counselRequestVersion) {
            return;
        }

        isLoadingCounselPage = false;
        isAnimatingCounselLoad = false;
        // 실패 시에도 목록을 한 번 그려서 빈 영역이 아무 안내 없이 방치되지 않게 한다.
        renderLeftCharHistory();
        appUtil.toast('대화 목록을 불러오는데 실패했습니다.');
    });
}

/**
 * 좌측 메뉴 채팅 히스토리 그리기
 */
const renderLeftCharHistory = function(){
    // [방어] 어떤 이유로든 배열이 아니면 빈 배열로 보정 (slice/forEach 에러 방지)
    if (!Array.isArray(counselQuestions)) counselQuestions = [];
    $('#counsel-list').empty();
    if (counselQuestions.length === 0) {
        $('#counsel-list').append(`
            <div class="p-4 text-center">
              <span class="text-sm text-gray-500 italic font-medium tracking-tight transition-colors">빈 대화</span>
            </div>
          `);
    } else {
        let itemsToShow = null;
        itemsToShow = counselQuestions.slice(0, counselDisplayLimit);

        //itemsToShow.some((q, index) => {
        for (const [index, q] of itemsToShow.entries()) {
            let isNew = isAnimatingCounselLoad && index >= counselDisplayLimit - 10;
            let animationClass = isNew ? "animate-in fade-in slide-in-from-top-8 duration-700" : "";
            let animStyle = isNew ? `style="animation-fill-mode: both; animation-delay: ${(index % 10) * 80}ms;"` : "";

            //현재 선택된 대화내역인지
            let activeChatItem = "";
            if(q.id !== null && q.id > 0 && chatSeq !== null && chatSeq > 0){
                activeChatItem = (q.id === chatSeq) ? "active-chat-item" : "";
            }

            // 대화방 제목은 사용자가 직접 입력한 값이므로 반드시 이스케이프한다.
            const safeText = appUtil.escapeHtml(q.text);                       // 전체 제목(툴팁용)
            const safeShortText = appUtil.escapeHtml(truncateTitle(q.text));   // 표시용(20자)
            const safeId = appUtil.escapeAttr(q.id);

            $('#counsel-list').append(`
                <div class="${activeChatItem} relative p-2.5 text-[15px] text-gray-600 dark:text-gray-300 hover:text-gray-900 dark:hover:text-white flex items-center justify-between group cursor-pointer hover:bg-gray-200 dark:hover:bg-white/5 rounded-md transition-all ${animationClass}" ${animStyle}>
                  <span class="search-trigger truncate leading-tight pr-4 flex-1 transition-colors" data-tooltip="${safeText}" data-id="${safeId}">${safeShortText}</span>
                  <button class="menu-btn shrink-0 p-1 rounded-md transition-colors opacity-0 group-hover:opacity-100 text-gray-400 dark:text-gray-500 hover:text-gray-900 dark:hover:text-white hover:bg-gray-300 dark:hover:bg-white/10" data-id="${safeId}" data-type="counsel" aria-label="대화 메뉴 열기">
                    <i data-lucide="more-vertical" class="w-4 h-4"></i>
                  </button>

                  <!-- Dropdown -->
                  <div class="popup-menu hidden fixed w-[150px] bg-white dark:bg-[#333333] border border-gray-200 dark:border-[#4a4a4a] rounded-lg shadow-2xl z-[100] py-1.5 animate-in fade-in zoom-in-95 duration-100 transition-colors" id="menu-counsel-${safeId}">
                    <div class="action-archive flex items-center gap-2.5 px-3 py-2 text-[12px] text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-white/5 hover:text-gray-900 dark:hover:text-white transition-colors cursor-pointer whitespace-nowrap" data-id="${safeId}">
                      <i data-lucide="archive" class="w-3 h-3"></i> 아카이브 보관
                    </div>
                    <div class="action-rename flex items-center gap-2.5 px-3 py-2 text-[12px] text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-white/5 hover:text-gray-900 dark:hover:text-white transition-colors cursor-pointer whitespace-nowrap" data-id="${safeId}" data-type="counsel">
                      <i data-lucide="arrow-left-right" class="w-3 h-3"></i> 이름변경
                    </div>
                    <div class="action-delete flex items-center gap-2.5 px-3 py-2 text-[12px] text-red-500 dark:text-red-400 hover:bg-gray-100 dark:hover:bg-white/5 hover:text-red-600 dark:hover:text-red-300 transition-colors cursor-pointer whitespace-nowrap" data-id="${safeId}" data-type="counsel">
                      <i data-lucide="trash-2" class="w-3 h-3"></i> 삭제
                    </div>
                  </div>
                </div>
          `);
        }

        //표시하지 않은 데이터나 다음 Slice가 있으면 더보기 생성
        if (counselQuestions.length > 3 || counselHasNext) {
            let canLoadMore = counselQuestions.length > counselDisplayLimit || counselHasNext;
            let icon = canLoadMore ? 'arrow-down' : 'arrow-up';
            let text = canLoadMore ? '더보기' : '접기';

            $('#counsel-list').append(`
            <div class="flex justify-center py-2.5 mt-1 bg-gradient-to-t from-gray-50 dark:from-[#2c2c2c] to-transparent">
              <div id="toggle-counsel-list" class="group/toggle h-[28px] min-w-[28px] rounded-full bg-gray-200 dark:bg-[#3a3a3a] flex items-center justify-center transition-all duration-300 hover:px-3.5 text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white cursor-pointer border border-transparent hover:border-gray-300 dark:hover:border-[#4a4a4a] shadow-sm hover:shadow-md">
                <i data-lucide="${icon}" class="w-4 h-4 transition-transform group-hover/toggle:hidden"></i>
                <span class="hidden group-hover/toggle:block text-[12px] font-bold tracking-wide whitespace-nowrap">${text}</span>
              </div>
            </div>
          `);
        }
    }

    //아이콘 생성
    lucide.createIcons();
}

/**
 * 좌측 메뉴 아카이브 채팅 히스토리 가져오기
 */
const getLeftArchiveHistory = function(reset = true, expandAfterLoad = false){
    if (!reset && (isLoadingArchivePage || !archiveHasNext)) {
        return;
    }

    if (reset) {
        archiveRequestVersion += 1;
    }

    const requestVersion = archiveRequestVersion;
    const requestedPage = reset ? 0 : archiveNextPage;
    isLoadingArchivePage = true;

    appUtil.ajaxPost('/chat/api/left_archiveyn_history', {
        page: requestedPage,
        size: CHAT_LIST_PAGE_SIZE
    }, function (result){
        if (requestVersion !== archiveRequestVersion) {
            return;
        }

        const newItems = Array.isArray(result.content) ? result.content : [];
        archivedQuestions = reset
            ? newItems
            : mergeChatRoomItems(archivedQuestions, newItems);
        archiveNextPage = Number(result.page ?? requestedPage) + 1;
        archiveHasNext = result.hasNext === true;

        if (reset) {
            archiveDisplayLimit = 3;
        } else if (expandAfterLoad) {
            archiveDisplayLimit = Math.min(
                archiveDisplayLimit + CHAT_LIST_PAGE_SIZE,
                archivedQuestions.length
            );
        }

        if(chatSeq !== null && chatSeq > 0){
            let itemIndex = archivedQuestions.findIndex(q => Number(q.id) === Number(chatSeq));
            if (itemIndex > 2) {
                archiveDisplayLimit = Math.max(archiveDisplayLimit, itemIndex + 1);
            }
        }

        isLoadingArchivePage = false;
        renderLeftArchiveHistory();
        refreshSearchListIfVisible();
        focusActiveChatInSidebar();
        isAnimatingArchiveLoad = false;
    }, function(){
        if (requestVersion !== archiveRequestVersion) {
            return;
        }

        isLoadingArchivePage = false;
        isAnimatingArchiveLoad = false;
        // 실패 시에도 목록을 한 번 그려서 빈 영역이 아무 안내 없이 방치되지 않게 한다.
        renderLeftArchiveHistory();
        appUtil.toast('아카이브 목록을 불러오는데 실패했습니다.');
    });
}

/**
 * 좌측 메뉴 아카이브 채팅 히스토리 그리기
 */
const renderLeftArchiveHistory = function(){
    // [방어] 어떤 이유로든 배열이 아니면 빈 배열로 보정 (slice/forEach 에러 방지)
    if (!Array.isArray(archivedQuestions)) archivedQuestions = [];
    $('#archive-list').empty();
    if (archivedQuestions.length === 0) {
        $('#archive-list').append(`
            <div class="p-4 text-center">
              <span class="text-sm text-gray-500 italic font-medium tracking-tight transition-colors">빈 저장함</span>
            </div>
          `);
    } else {
        const itemsToShow = archivedQuestions.slice(0, archiveDisplayLimit);

        itemsToShow.forEach((q, index) => {
            let isNew = isAnimatingArchiveLoad && index >= archiveDisplayLimit - 10;
            let animationClass = isNew ? "animate-in fade-in slide-in-from-top-8 duration-700" : "";
            let animStyle = isNew ? `style="animation-fill-mode: both; animation-delay: ${(index % 10) * 80}ms;"` : "";

            //현재 선택된 대화내역인지
            let activeChatItem = "";
            if(q.id !== null && q.id > 0 && chatSeq !== null && chatSeq > 0){
                activeChatItem = (q.id === chatSeq) ? "active-chat-item" : "";
            }

            // 대화방 제목은 사용자가 직접 입력한 값이므로 반드시 이스케이프한다.
            const safeText = appUtil.escapeHtml(q.text);                       // 전체 제목(툴팁용)
            const safeShortText = appUtil.escapeHtml(truncateTitle(q.text));   // 표시용(20자)
            const safeId = appUtil.escapeAttr(q.id);

            $('#archive-list').append(`
                  <div class="${activeChatItem} relative p-2.5 text-[15px] text-gray-600 dark:text-gray-300 hover:text-gray-900 dark:hover:text-white flex items-center justify-between group cursor-pointer hover:bg-gray-200 dark:hover:bg-white/5 rounded-md transition-all ${animationClass}" ${animStyle}>
                    <span class="search-trigger truncate leading-tight pr-4 flex-1 transition-colors" data-tooltip="${safeText}" data-id="${safeId}">${safeShortText}</span>
                    <button class="menu-btn shrink-0 p-1 rounded-md transition-colors opacity-0 group-hover:opacity-100 text-gray-400 dark:text-gray-500 hover:text-gray-900 dark:hover:text-white hover:bg-gray-300 dark:hover:bg-white/10" data-id="${safeId}" data-type="archive" aria-label="아카이브 메뉴 열기">
                      <i data-lucide="more-vertical" class="w-4 h-4"></i>
                    </button>

                    <!-- Dropdown -->
                    <div class="popup-menu hidden fixed w-[150px] bg-white dark:bg-[#333333] border border-gray-200 dark:border-[#4a4a4a] rounded-lg shadow-2xl z-[100] py-1.5 animate-in fade-in zoom-in-95 duration-100 transition-colors" id="menu-archive-${safeId}">
                      <div class="action-unarchive flex items-center gap-2.5 px-3 py-2 text-[12px] text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-white/5 hover:text-gray-900 dark:hover:text-white transition-colors cursor-pointer whitespace-nowrap" data-id="${safeId}">
                        <i data-lucide="archive-restore" class="w-3 h-3"></i> 아카이브 보관 취소
                      </div>
                      <div class="action-rename flex items-center gap-2.5 px-3 py-2 text-[12px] text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-white/5 hover:text-gray-900 dark:hover:text-white transition-colors cursor-pointer whitespace-nowrap" data-id="${safeId}" data-type="archive">
                        <i data-lucide="arrow-left-right" class="w-3 h-3"></i> 이름변경
                      </div>
                    </div>
                  </div>
                `);
        });

        if (archivedQuestions.length > 3 || archiveHasNext) {
            let canLoadMore = archivedQuestions.length > archiveDisplayLimit || archiveHasNext;
            let icon = canLoadMore ? 'arrow-down' : 'arrow-up';
            let text = canLoadMore ? '더보기' : '접기';

            $('#archive-list').append(`
                  <div class="flex justify-center py-2.5 mt-1 bg-gradient-to-t from-gray-50 dark:from-[#2c2c2c] to-transparent">
                    <div id="toggle-archive-list" class="group/toggle h-[28px] min-w-[28px] rounded-full bg-gray-200 dark:bg-[#3a3a3a] flex items-center justify-center transition-all duration-300 hover:px-3.5 text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white cursor-pointer border border-transparent hover:border-gray-300 dark:hover:border-[#4a4a4a] shadow-sm hover:shadow-md">
                      <i data-lucide="${icon}" class="w-4 h-4 transition-transform group-hover/toggle:hidden"></i>
                      <span class="hidden group-hover/toggle:block text-[12px] font-bold tracking-wide whitespace-nowrap">${text}</span>
                    </div>
                  </div>
                `);
        }
    }
    lucide.createIcons();
}

// 특정 대화방이 활성화되었을 때 좌측 메뉴 스크롤 및 레이아웃을 정리해주는 함수
const focusActiveChatInSidebar = function() {
    const $activeItem = $('.active-chat-item');

    if ($activeItem.length > 0) {
        // 1. 활성화된 아이템이 속한 부모 컨테이너와 스크롤 영역 찾기
        const $container = $activeItem.closest('#counsel-section-container, #archive-section-container');
        const $wrapper = $activeItem.closest('.custom-scrollbar');

        // ★ 해결 포인트: flex-1을 빼고 shrink로 변경!
        // 이렇게 하면 4개일 땐 딱 4개 크기만 차지하고, 20개일 땐 스크롤이 생깁니다.
        $container.removeClass('shrink-0 flex-1').addClass('shrink');

        // ★ 3. 선택된 아이템이 스크롤 영역의 중앙에 오도록 부드럽게 자동 스크롤
        setTimeout(() => {
            const itemTop = $activeItem.position().top;
            const currentScroll = $wrapper.scrollTop();
            const wrapperHeight = $wrapper.height();
            const itemHeight = $activeItem.outerHeight();

            $wrapper.animate({
                scrollTop: currentScroll + itemTop - (wrapperHeight / 2) + (itemHeight / 2)
            }, 300);
        }, 50);
    }
}

//추천메시지 뿌리기
const renderRecommended = function() {
    $('#recommended-list').empty();
    $('#bottom-recommended-list').empty();

    recommendedQuestions.forEach(q => {
        const safeQ = appUtil.escapeHtml(q);

        // 중앙 추천 영역
        $('#recommended-list').append(`
            <div class="search-recommend-trigger flex items-start gap-2 cursor-pointer group" data-query="${safeQ}" role="button" tabindex="0">
              <span class="text-gray-400 dark:text-gray-500 font-bold mt-0.5 px-1 transition-colors">•</span>
              <span class="text-[15px] text-gray-600 dark:text-gray-400 group-hover:text-gray-900 dark:group-hover:text-gray-200 group-hover:underline transition-all">${safeQ}</span>
            </div>
          `);

        // 하단 팝업 추천 영역
        $('#bottom-recommended-list').append(`
            <div class="search-recommend-trigger px-3 py-1.5 rounded-lg hover:bg-gray-100 dark:hover:bg-white/5 transition-colors cursor-pointer group flex items-center gap-2.5" data-query="${safeQ}" role="button" tabindex="0">
              <div class="w-1.5 h-1.5 rounded-full bg-gray-300 dark:bg-gray-600 group-hover:bg-blue-500 transition-colors shrink-0"></div>
              <span class="text-[13px] text-gray-600 dark:text-gray-300 group-hover:text-gray-900 dark:group-hover:text-gray-100 leading-snug break-keep transition-colors flex-1">${safeQ}</span>
            </div>
          `);
    });
}

// 1. 좌측 사이드바 크기 변경
const updateSidebarUI = function() {
    if (isSidebarOpen) {
        $('#sidebar').removeClass('w-[72px]').addClass('w-[250px]');
        $('#sidebar-inner').removeClass('px-2').addClass('px-4');
        $('#logo-section').removeClass('justify-center').addClass('justify-between px-2');
        $('#sidebar-brand').show();
        // 펼침: 새 대화 + 검색 전체 버튼(#primary-actions) 노출, 접힘 아이콘 컬럼 숨김
        $('#primary-actions').show();
        $('.sidebar-text').show();
        $('#sidebar-lists').removeClass('pr-0').addClass('pr-1');
        $('#sidebar-expanded-content').show();
        $('#sidebar-collapsed-content').removeClass('flex').addClass('hidden');
    } else {
        $('#sidebar').removeClass('w-[250px]').addClass('w-[72px]');
        $('#sidebar-inner').removeClass('px-4').addClass('px-2');
        $('#logo-section').removeClass('justify-between px-2').addClass('justify-center');
        $('#sidebar-brand').hide();
        // 접힘: 전체 버튼 숨기고, 아이콘 컬럼(새 대화·검색·대화·아카이브)만 노출 (검색 아이콘 중복 방지)
        $('#primary-actions').hide();
        $('.sidebar-text').hide();
        $('#sidebar-lists').removeClass('pr-1').addClass('pr-0');
        $('#sidebar-expanded-content').hide();
        $('#sidebar-collapsed-content').removeClass('hidden').addClass('flex');
    }
}


//답변 가져오기
async function streamAnswer(questionText) {
    // 빈 질문(공백만 포함)은 전송하지 않는다.
    // 버튼 클릭·Enter·추천질문 등 모든 진입점을 여기 한 곳에서 막는다.
    if (appUtil.removeAllSpacesAndIsBlank(questionText)) {
        return;
    }

    // HTML 태그·스크립트가 섞인 질문은 전송하지 않는다. (입력창 내용은 그대로 두어 수정할 수 있게 한다)
    // 안내용 사전 검사이며, 실제 차단은 서버(InputGuard)가 한다.
    if (appUtil.containsMarkup(questionText)) {
        appUtil.toast(appUtil.MARKUP_REJECT_MESSAGE);
        return;
    }

    if (isGenerating) {
        return;   // 생성 중 중복 전송 방지
    }

    isGenerating = true;

    // [추가] 전송 아이콘 숨기고, 중지(네모) 아이콘 켜기
    $('.icon-send').addClass('hidden');
    $('.icon-stop').removeClass('hidden');

    // [추가] 질문을 전송했으니 입력창 내용 삭제 (늘어난 높이도 함께 되돌린다)
    resetInputs();

    // 1. 임시 시퀀스 생성 (화면에 여러 개의 채팅 블록을 구분하기 위한 용도)
    const tempSeq = Date.now();
    activeStreamSeq = tempSeq; // 중지 버튼이 이 블록의 로딩 UI를 정리할 수 있도록 기억

    // 2. 초기 뷰 숨기고, 새로운 채팅 블록(껍데기)을 렌더링
    $('#view-search').hide();
    $('#view-initial').hide();
    $('#bottom-input-area').removeClass('hidden');

    // 템플릿 함수를 호출해서 빈 블록(질문 + 뱅글뱅글 로딩창)을 화면 끝에 붙임 (현재 검색 방식 배지 포함)
    $('#view-area').append(createChatBlockHtml(tempSeq, questionText, getRetrievalMode()));
    lucide.createIcons(); // 아이콘 렌더링

    // 시작 시 즉시(빠릿하게) 바닥으로 스냅한 뒤 자동 추종을 켠다.
    // (먼 거리를 천천히 내려오지 않고 바로 질문 위치로 이동 → 이후 글자만 부드럽게 위로 올라간다.)
    scrollChatToBottom(false);
    startAutoFollow();

    let buffer = "";
    let accumulatedText = "";

    // 질문하기 전 방 번호 상태 기억 (방이 없었으면 새 채팅)
    const isNewChat = !currentChatSeq;

    // 기존 대화방이면 보내는 즉시 목록 최상단으로 올린다 (새 대화방은 chatInfo 수신 시 목록 갱신됨)
    if (!isNewChat) {
        promoteChatToTop(currentChatSeq);
    }

    //선택된 llm 모델
    const targetModel = appUtil.storageGet('selectedModel', 'v1.2.1');

    try {
        // CSRF 토큰 세팅 (기존 로직 유지)
        const csrfMeta = document.querySelector('meta[name="_csrf"]');
        const csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
        const headers = { 'Content-Type': 'application/json' };
        if (csrfMeta && csrfHeaderMeta) headers[csrfHeaderMeta.content] = csrfMeta.content;

        // 통신 시작 전에 컨트롤러를 새로 만듭니다.
        abortController = new AbortController();

        // ★ 백엔드 통신 시작!
        const response = await fetch('/chat/api/stream', {
            method: 'POST',
            headers: headers,
            signal: abortController.signal, // <--- 전송, 중지를 위한 시그널
            body: JSON.stringify({
                question: questionText,
                chatSeq: currentChatSeq, // 첫 질문은 null, 이후엔 방 번호 전송
                model: targetModel,
                retrievalMode: getRetrievalMode() // 검색 방식(1: 기본, 2: 청크 확장)
            })
        });

        if (!response.ok) {
            // 400: 서버 입력 검증에서 거절됨 (prod 는 오류 메시지를 본문에 싣지 않으므로 화면에서 문구를 정한다)
            const error = new Error("API Error");
            if (response.status === 400) error.name = 'BadRequestError';
            // 401: 세션 만료 (서버가 채팅방 생성·저장 전에 막으므로 이 질문은 저장되지 않는다)
            if (response.status === 401) error.name = 'SessionExpiredError';
            throw error;
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");

        let isRoomInfoEvent = false;
        let isSourcesEvent = false;     //출처 이벤트 감지 플래그(미사용?)
        let isUsedSourcesEvent = false; // 실제 채택된 출처 이벤트
        let isFirstTextChunk = true;

        // 텍스트가 들어갈 최종 목적지 Div
        const targetDiv = document.getElementById(`llm-content-${tempSeq}`);

        // 타자치는 듯한 커서 깜빡임 효과 추가 (CSS 필요)
        targetDiv.classList.add("cursor-blink");

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop(); // 잘린 마지막 줄은 다음 번으로 넘김

            for (const line of lines) {
                const trimmedLine = line.trim();

                // [서버 이벤트 확인] 방 번호(chatInfo) 발급, 출처(sources / used_sources) 발급
                //
                // SSE 규격상 이벤트 이름은 줄 맨 앞의 "event:" 로만 판별해야 한다.
                // includes() 로 검사하면 답변 본문에 "event:" 라는 문자열이 들어있는 순간
                // 그 줄이 이벤트로 오인되어 화면에 출력되지 않는다.
                if (trimmedLine.startsWith('event:')) {
                    const eventName = trimmedLine.slice('event:'.length).trim();

                    isRoomInfoEvent = (eventName === 'chatInfo');       //방번호 발급
                    isUsedSourcesEvent = (eventName === 'used_sources');//최종 채택된 출처
                    isSourcesEvent = (eventName === 'sources');         //전체 검색 출처
                }
                // [데이터 파싱]
                else if (trimmedLine.startsWith('data:')) {
                    const dataPart = trimmedLine.replace('data:', '').trim();
                    if (!dataPart) continue;

                    // (A) 방 번호 저장 및 라우팅 로직
                    if (isRoomInfoEvent) {
                        currentChatSeq = parseInt(dataPart);
                        isRoomInfoEvent = false;

                        // 새 채팅방이면 URL을 바꾸고 목록을 갱신
                        if (isNewChat) {
                            let newUrl = '/chat/main/' + currentChatSeq;
                            // 새로고침 없이 URL만 변경 (제미나이/GPT 방식)
                            window.history.pushState({ chatSeq: currentChatSeq }, '', newUrl);

                            //전역변수에 방번호 대입
                            chatSeq = currentChatSeq;
                            // 좌측 목록 갱신 (기존 함수 호출)
                            if (typeof getLeftChatHistory === 'function') {
                                getLeftChatHistory();
                            }
                        }
                    }
                    // (B) 최종 채택된 출처(used_sources) 그리기 ★
                    else if(isUsedSourcesEvent){
                        try{
                            // 1. 위쪽 출처 로딩창(뱅글뱅글) 끄기
                            $(`#source-loading-state-${tempSeq}`).addClass('hidden');
                            // 2. 위쪽 출처 카드 컨테이너 켜기
                            $(`#source-card-container-${tempSeq}`).removeClass('hidden');

                            // 3. 카드 그리기
                            createSourceBlockHtml(dataPart, tempSeq);
                        } catch (e) {
                            console.error("채택 출처 파싱 실패:", e);
                        } finally {
                            isUsedSourcesEvent = false;
                        }
                    }
                    //출처 데이터(배열) 파싱 및 화면에 그리기(미사용)
                    else if(isSourcesEvent){
                        try{
                            //createSourceBlockHtml(dataPart, tempSeq);    //출처 html생성
                        }catch (e) {
                            //console.error("출처 데이터 파싱 실패:", e);
                        }finally {
                            isSourcesEvent = false; // 처리 후 플래그 끄기
                        }
                    }
                    // (B) 실제 답변 텍스트 조각(Chunk) 처리
                    else {
                        try {
                            const data = JSON.parse(dataPart);
                            if (data.content) {
                                // ★ 첫 번째 글자가 도착하면 로딩창(뱅글뱅글)을 끄고 결과창을 켬!
                                if (isFirstTextChunk) {
                                    $(`#answer-loading-state-${tempSeq}`).addClass('hidden').removeClass('flex');
                                    $(`#answer-result-state-${tempSeq}`).removeClass('hidden');
                                    isFirstTextChunk = false;
                                }
                                accumulatedText += data.content;
                            }
                        } catch (e) {
                            // JSON 파싱 에러 무시
                        }
                    }
                }
            }

            // 렌더링 (글자가 화면에 실시간으로 타자 쳐짐)
            if (!isFirstTextChunk) {
                targetDiv.innerHTML = appUtil.renderMarkdown(appUtil.normalizeCitationLabels(accumulatedText));
            }

            // 추종 중이면 새로 늘어난 만큼 부드럽게 바닥으로 따라간다.
            // (사용자가 위로 스크롤해 추종이 꺼져 있으면 아무것도 하지 않아 그 자리에 머문다.)
            requestFollow();
        }

    } catch (error) {
        console.error(error);
        // 에러 발생 시 로딩창 모두 끄고 결과창 켜기
        $(`#source-loading-state-${tempSeq}`).addClass('hidden');
        $(`#answer-loading-state-${tempSeq}`).addClass('hidden').removeClass('flex');
        $(`#answer-result-state-${tempSeq}`).removeClass('hidden');

        const targetDiv = document.getElementById(`llm-content-${tempSeq}`);
        if (targetDiv) {
            // ★ [핵심] 사용자가 중지 버튼을 눌러서 강제로 끊은 경우인지 확인!
            if (error.name === 'AbortError') {
                targetDiv.innerHTML += "<br><br><span style='color:#6b7280; font-weight:bold;'>[답변 생성이 중지되었습니다.]</span>";
            } else if (error.name === 'SessionExpiredError') {
                targetDiv.innerHTML += "<br><span style='color:red; font-weight:bold;'>[세션이 만료되었습니다]</span>";
            } else if (error.name === 'BadRequestError') {
                targetDiv.innerHTML += "<br><span style='color:red; font-weight:bold;'>[처리할 수 없는 질문입니다. " + appUtil.escapeHtml(appUtil.MARKUP_REJECT_MESSAGE) + "]</span>";
            } else {
                targetDiv.innerHTML += "<br><span style='color:red; font-weight:bold;'>[통신 중 오류가 발생했습니다]</span>";
            }
        }

        // 세션 만료: SSO 실패와 같은 alert 을 띄우고 확인 시 포털로 이동한다.
        if (error.name === 'SessionExpiredError') {
            appUtil.handleSessionExpired();
        }
    } finally {
        // 만약 출처 데이터를 못 받고 끝났다면, 출처 로딩창도 숨겨줍니다 (무한 로딩 방지)
        $(`#source-loading-state-${tempSeq}`).addClass('hidden');

        // 통신 완료: 커서 깜빡임 제거 및 스크롤 바닥 고정
        const targetDiv = document.getElementById(`llm-content-${tempSeq}`);
        if (targetDiv) {
            targetDiv.classList.remove("cursor-blink");

            // 답변 블록 바로 밑에 복사 버튼을 붙인다.
            $(targetDiv).closest('.ai-answer-block').after(createCopyButtonHtml());

            // 복사 원본(마크다운)은 DOM 속성이 아닌 jQuery 메모리에 보관
            $(targetDiv).data('markdown', appUtil.normalizeCitationLabels(accumulatedText));

            // 방금 새로 만든 버튼의 루시드 아이콘(copy)을 화면에 그려줍니다.
            lucide.createIcons();
        }

        // 종료: 추종 중이었다면 마지막으로 바닥에 부드럽게 붙이고, 추종을 끈다.
        // (사용자가 위로 스크롤해 이미 추종이 꺼져 있으면 그 자리에 그대로 둔다.)
        if (streamAutoFollow) {
            scrollChatToBottom(true, 300);
        }
        stopAutoFollow();

        isGenerating = false;
        if (activeStreamSeq === tempSeq) {
            activeStreamSeq = null;
        }

        // 중지 아이콘 끄고, 다시 전송 아이콘 켜기
        $('.icon-stop').addClass('hidden');
        $('.icon-send').removeClass('hidden');
    }
}

/**
 * 답변 하단 "복사" 액션바 HTML을 만들어주는 함수
 * (실시간 스트리밍 답변과 과거 대화 내역 양쪽에서 동일하게 사용)
 */
const createCopyButtonHtml = function() {
    return `
    <div class="action-bar-area flex justify-end pt-2 border-t border-gray-200 dark:border-[#3a3a3a]/50 mt-4 transition-colors px-2 md:px-4">
        <button class="btn-copy-answer flex items-center gap-1.5 px-3 py-1.5 text-[13px] text-gray-600 dark:text-gray-400 bg-gray-100 dark:bg-[#2a2a2a] hover:bg-gray-200 dark:hover:bg-[#3a3a3a] hover:text-gray-900 dark:hover:text-gray-200 border border-gray-200 dark:border-[#444] rounded-lg transition-colors shadow-sm" aria-label="답변 복사">
            <i data-lucide="copy" class="w-3.5 h-3.5"></i>
            <span class="font-medium tracking-wide">복사</span>
        </button>
    </div>`;
}

// 새로운 질문/답변 블록의 HTML을 만들어주는 함수
const createChatBlockHtml = function(chatSeq, questionText, retrievalMode) {
    // 질문은 사용자 입력이므로 화면에 꽂기 전에 반드시 이스케이프한다.
    const safeQuestion = appUtil.escapeHtml(questionText);

    // 검색 방식 배지 (라이브 질문에만 표시. 과거 이력 로드 시엔 생략)
    const isChunk = Number(retrievalMode) === 2;
    const modeBadge = retrievalMode
        ? `<span class="ml-3 inline-block text-[11px] font-bold px-2 py-0.5 rounded-full ${isChunk ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-500/20 dark:text-emerald-300' : 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300'}">검색: ${isChunk ? '청크 확장' : '기본'}</span>`
        : '';

    return `
    <div id="view-result_${chatSeq}" class="chat-turn-block p-4 md:p-8 max-w-5xl mx-auto w-full space-y-2">
        <div class="flex items-start gap-3 md:gap-4 px-2 md:px-4 pt-2 pb-3 border-b border-gray-200 dark:border-[#3a3a3a] transition-colors">
            <i data-lucide="message-circle" class="w-6 h-6 text-gray-400 dark:text-gray-500 mt-0.5 shrink-0 hidden sm:block"></i>
            <h2 class="text-xl md:text-2xl font-bold text-gray-900 dark:text-gray-100 leading-snug break-keep">${safeQuestion}</h2>
        </div>

        <div class="pt-0 pb-0 transition-colors px-2 md:px-4">
            <div class="inline-flex items-center border border-gray-300 dark:border-[#4a4a4a] rounded-full p-0.5 transition-colors">
                <button class="chat-tab-btn tab-answer active px-10 py-1.5 rounded-full text-[14px] transition-all bg-gray-200 text-gray-900 dark:bg-[#5a5a5a] dark:text-gray-200" data-seq="${chatSeq}" data-target="answer">답변</button>
                <button class="chat-tab-btn tab-search px-10 py-1.5 rounded-full text-[14px] transition-all text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300" data-seq="${chatSeq}" data-target="search">검색결과</button>
            </div>
            ${modeBadge}
        </div>

        <div class="animate-in fade-in duration-500 pt-0">
            <div id="content-answer-${chatSeq}" class="pb-8 space-y-4">
                
                <div class="source-section px-2 pt-4">
                    <div id="source-loading-state-${chatSeq}" class="h-[80px] flex items-center px-4 rounded-xl border border-transparent bg-gray-50/80 dark:bg-[#1e1e1e]/50 transition-colors animate-pulse">
                        <div class="char-fade-loader text-[15px] font-bold text-gray-500 dark:text-gray-400 tracking-widest">
                            <span>채</span><span>택</span><span>된</span><span> </span><span>출</span><span>처</span><span>를</span><span> </span><span>분</span><span>석</span><span>중</span><span>입</span><span>니</span><span>다</span><span>.</span><span>.</span><span>.</span>
                        </div>
                    </div>
                    
                    <div id="source-card-container-${chatSeq}" class="hidden flex flex-nowrap gap-2 w-full overflow-x-auto custom-scrollbar pb-1"></div>
                </div>

                <div class="answer-section pt-2">
                    <div id="answer-loading-state-${chatSeq}" class="flex items-center gap-4 px-4 pb-4">
                        <div class="relative flex items-center justify-center w-10 h-10">
                            <div class="absolute inset-0 border-[3px] border-gray-200 dark:border-[#333] border-t-blue-500 dark:border-t-[#00ffd1] rounded-full animate-spin"></div>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" class="text-blue-600 dark:text-[#00ffd1] shrink-0"><path d="M6,3 H21 V4.5 Z" /><path d="M21,6 V21 H19.5 Z" /><path d="M18,21 H3 V19.5 Z" /><path d="M3,18 V3 H4.5 Z" /><circle cx="16" cy="16" r="2" /></svg>
                        </div>
                        <p class="text-[16px] font-bold text-gray-800 dark:text-gray-200 tracking-wide">
                            답변을 생성하고 있습니다<span class="animate-pulse">...</span>
                        </p>
                    </div>

                    <div id="answer-result-state-${chatSeq}" class="hidden flex flex-col gap-10">
                        <div class="ai-answer-block">
                            <div class="flex items-start gap-3 px-2 md:px-4">
                                <svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="w-[22px] h-[22px] shrink-0 mt-0.5 text-blue-600 dark:text-blue-400" aria-hidden="true"><path d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"/></svg>
                                
                                <div id="llm-content-${chatSeq}" class="llm-content flex-1 min-w-0 text-[15px] leading-relaxed" aria-live="polite" aria-atomic="false"></div>
                            </div>
                        </div>
                    </div>
                </div>

            </div>

            <!-- 검색결과는 문서 수가 많아(벡터+키워드) 턴 블록이 과하게 길어진다.
                 탭 안에서만 스크롤되게 해 답변과 다음 턴이 멀리 밀려나지 않도록 한다. -->
            <div id="content-search-${chatSeq}" class="hidden pb-4 px-5 max-h-[60vh] overflow-y-auto custom-scrollbar">
            </div>
        </div>
    </div>`;
}

const createSourceBlockHtml = function(dataPart, tempSeq){
    let sources;
    try {
        sources = typeof dataPart === 'string' ? JSON.parse(dataPart) : dataPart;
    } catch (e) {
        console.error('출처 데이터 파싱 실패:', e);
        return;
    }

    if (!Array.isArray(sources)) {
        return;
    }

    // 1. 답변 탭 상단의 가로 스크롤 컨테이너
    const $sourceContainer = $(`#source-card-container-${tempSeq}`);
    // 2. 검색결과 탭 내부의 세로 리스트 컨테이너
    const $searchTabContainer = $(`#content-search-${tempSeq}`);

    $sourceContainer.empty();
    $searchTabContainer.empty();

    // 서버는 답변이 실제로 인용한 출처만 내려준다. 인용이 하나도 없으면(근거 없음 답변 등)
    // 빈 영역을 그대로 두지 않고 안내 문구를 남긴다.
    if (sources.length === 0) {
        $searchTabContainer.append(
            '<div class="py-8 text-center text-gray-500 text-[14px]">답변에 인용된 출처가 없습니다.</div>'
        );
        return;
    }

    sources.forEach((src, idx) => {
        // 검색엔진/RAG 원문은 신뢰할 수 없으므로 화면에 꽂기 전에 반드시 이스케이프한다.
        let sourceId = appUtil.escapeAttr(src.id || '');
        let rawTitle = src.title || '제목 없음';
        let title = appUtil.escapeHtml(rawTitle);
        // 답변 본문의 '[문서N]' 표기와 카드 번호를 맞춘다. (없으면 표시 순번으로 폴백)
        let seqNo = src.docNo || (idx + 1);

        // 원문 정리: 리터럴 "\n" 복원 + 개행 통일. 이 값이 우측 패널 전문의 원본이 된다.
        let fullText = appUtil.normalizeSourceText(src.content);
        // 카드용 축약본(개행을 공백으로 흡수) / 검색결과용 발췌(개행 유지)
        let previewHtml = appUtil.escapeHtml(appUtil.toPreviewText(src.content));
        let excerptHtml = appUtil.escapeHtml(fullText);

        // 확장 메타데이터(청크 ID·버전·시행일자) 라벨
        let version = appUtil.escapeHtml(src.version || '');
        let effectiveDate = appUtil.escapeHtml(src.effectiveDate || '');
        let docId = appUtil.escapeHtml(src.id || '');
        // 원문 사이트 바로가기용 문서번호.
        // 청크 ID 는 "{문서ID}_{청크번호}" 형식이고, 앞의 문서ID 가 원문 바로가기의 문서 식별자다.
        let originalDocId = appUtil.escapeAttr(String(src.id || '').split('_')[0]);
        let metaParts = [];
        if (docId) metaParts.push('문서 ' + docId);
        if (version) metaParts.push('버전 ' + version);
        if (effectiveDate) metaParts.push('시행 ' + effectiveDate);
        let metaHtml = metaParts.length
            ? `<div class="text-[10px] text-gray-400 dark:text-gray-500 mt-0.5 truncate">${metaParts.join(' · ')}</div>`
            : '';

        // 이 문서가 직접 검색된 것인지, 검색 결과의 앞/뒤 인접 구간인지 구분해 보여준다.
        // (인접 구간도 답변 근거로 쓰이므로, 사용자가 어디서 온 근거인지 알 수 있어야 한다)
        const isNeighbor = (src.origin === 'PREV' || src.origin === 'NEXT');
        const originBadge = isNeighbor
            ? `<span class="shrink-0 text-[11px] font-bold px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300" title="검색된 문서의 ${src.origin === 'PREV' ? '직전' : '직후'} 구간입니다">인접구간</span>`
            : `<span class="shrink-0 text-[11px] font-bold px-2 py-0.5 rounded-full bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300">검색결과</span>`;

        // ==========================================
        // [1] 답변 탭 (가로 스크롤 카드형) 그리기
        // ==========================================
        // 서버가 채택된 출처만 내려주므로 받은 것을 모두 그린다.
        // 폭을 고정하고 shrink-0 을 줘야 컨테이너가 실제로 가로 스크롤된다.
        // (기존 flex-1 min-w-0 은 카드들이 화면 폭을 나눠 갖고 찌그러져서 내용이 거의 안 보였다)
        // 카드는 "읽는 곳"이 아니라 어떤 문서가 채택됐는지 식별하는 칩이다.
        // 전문은 검색결과 탭과 우측 패널에서 보므로, 한 화면에 여러 장이 들어오도록
        // 제목 1줄 + 메타 1줄 + 본문 2줄로 높이를 고정해 촘촘하게 유지한다.
        const numberBadgeClass = isNeighbor
            ? 'bg-amber-100 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300'
            : 'bg-blue-100 text-blue-700 dark:bg-blue-500/20 dark:text-blue-300';

        const $card = $(`
            <div class="source-card-box w-[220px] shrink-0 border border-gray-300 dark:border-[#555] rounded-lg p-2.5 bg-gray-50 dark:bg-[#1e1e1e]/60 hover:bg-gray-100 dark:hover:bg-[#333] hover:border-blue-400 dark:hover:border-blue-500/60 transition-colors cursor-pointer flex flex-col gap-1" data-source-id="${sourceId}" data-version="${version}" data-effective-date="${effectiveDate}" data-original-doc-id="${originalDocId}">
              <div class="flex items-center gap-1.5 min-w-0">
                <span class="shrink-0 w-[16px] h-[16px] rounded-full ${numberBadgeClass} text-[10px] font-semibold flex items-center justify-center tabular-nums">${seqNo}</span>
                <span class="source-card-title flex-1 min-w-0 font-bold text-[12px] text-gray-700 dark:text-gray-200 leading-snug truncate" title="${title}">${title}</span>
              </div>
              ${metaHtml}
              <p class="text-[12px] text-gray-600 dark:text-gray-400 leading-snug line-clamp-2 break-keep transition-colors">${previewHtml}</p>
            </div>
        `);

        // 전문은 DOM 텍스트가 아니라 메모리에 보관한다. (발췌가 잘려도 패널엔 전문이 뜨도록)
        $card.data('fullContent', fullText).data('sourceTitle', rawTitle);
        $sourceContainer.append($card);

        // ==========================================
        // [2] 검색결과 탭 (세로 리스트형) 그리기
        // ==========================================


        const $row = $(`
            <div class="search-result-box py-5 border-b border-gray-200 dark:border-[#3a3a3a] first:pt-2 transition-colors cursor-pointer group" data-source-id="${sourceId}" data-version="${version}" data-effective-date="${effectiveDate}" data-original-doc-id="${originalDocId}">
              <div class="flex items-start gap-2 mb-1">
                 <span class="shrink-0 w-5 h-5 mt-0.5 rounded-full bg-gray-200 text-gray-600 dark:bg-white/10 dark:text-gray-300 text-[11px] font-semibold flex items-center justify-center tabular-nums">${seqNo}</span>
                 <h3 class="flex-1 min-w-0 text-gray-900 dark:text-gray-100 group-hover:text-blue-600 dark:group-hover:text-blue-400 font-bold text-[16px] leading-snug break-keep transition-colors">${title}</h3>
                 ${originBadge}
              </div>

              <div class="pl-7">
                  ${metaHtml}
                  <p class="search-body-text source-excerpt text-[14px] text-gray-600 dark:text-gray-300 leading-relaxed break-keep mt-1.5 transition-colors">${excerptHtml}</p>
                  <div class="excerpt-more hidden mt-1 text-[12px] font-medium text-blue-600 dark:text-blue-400">전문 보기 →</div>
              </div>
            </div>
        `);

        $row.data('fullContent', fullText).data('sourceTitle', rawTitle);
        $searchTabContainer.append($row);
    });

    // 발췌가 실제로 잘렸는지는 레이아웃이 잡힌 뒤에만 알 수 있다.
    markClampedExcerpts($searchTabContainer);
}

/**
 * 발췌 문단이 max-height 로 잘렸는지 판정해서 페이드와 "전문 보기" 안내를 켠다.
 * 검색결과 탭은 처음에 hidden 이라 높이가 0으로 측정되므로,
 * 탭이 표시되는 시점에 다시 호출해야 한다.
 * @param {jQuery} $scope 검사 범위
 */
const markClampedExcerpts = function($scope) {
    if (!$scope || $scope.length === 0) {
        return;
    }

    const measure = function() {
        $scope.find('.source-excerpt').each(function() {
            const isClamped = (this.scrollHeight - this.clientHeight) > 2;
            $(this).toggleClass('is-clamped', isClamped);
            $(this).siblings('.excerpt-more').toggleClass('hidden', !isClamped);
        });
    };

    // show() 직후 동기 측정은 값이 튀어서, 잘리지 않은 문단에도 페이드가 붙는다.
    // 레이아웃과 페인트가 끝난 다음 프레임에 측정한다.
    window.requestAnimationFrame(function() {
        window.requestAnimationFrame(measure);
    });

    // 웹폰트(Pretendard)가 늦게 적용되면 줄 높이가 바뀌므로 로드 완료 후 한 번 더 측정한다.
    if (document.fonts && document.fonts.status !== 'loaded') {
        document.fonts.ready.then(measure).catch(function() { /* 폰트 로드 실패는 무시 */ });
    }
}
