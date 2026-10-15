/**
 * Tailwind CSS 빌드 설정 (standalone CLI 전용, node/npm 불필요)
 *
 * 운영 환경에서는 CDN/JIT 스크립트를 사용하지 않고, 이 설정으로 미리 생성한
 * src/main/resources/static/css/tailwind.css 를 정적으로 서빙한다.
 *
 * 재생성 방법은 README.md 의 "프론트엔드 빌드" 항목 참고.
 *
 * [주의] content 에 static/js/** 와일드카드를 쓰면 안 된다.
 *        js/vendor/* 안의 라이브러리 소스까지 스캔해서 CSS가 수 MB로 불어난다.
 *        직접 작성한 파일만 명시적으로 나열한다.
 */
module.exports = {
    darkMode: 'class', // html.dark 클래스 기반 다크모드
    content: [
        './src/main/resources/templates/**/*.html',
        './src/main/resources/static/js/main.js',
        './src/main/resources/static/js/common/*.js',
    ],
    theme: {
        extend: {},
    },
    plugins: [],
};
