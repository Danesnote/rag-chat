package com.ragchat;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(RagChatApplication.class);
    }

    /**
     * 외장 Tomcat 배포 시 이 앱의 세션 기본 유휴 시간을 지정한다.
     *
     * <p>0 이하면 세션이 유휴 시간으로 만료되지 않는다. Tomcat conf/web.xml 의 기본값(30분)보다 우선한다.
     * ({@code server.servlet.session.timeout} 은 내장 Tomcat 전용이라 외장 배포에서는 적용되지 않는다)
     *
     * <p>※ 브라우저 종료, WAS 재기동·재배포 시에는 세션이 사라지므로 SSO 재진입이 필요하다.
     */
    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        super.onStartup(servletContext);
        servletContext.setSessionTimeout(-1);
    }
}
