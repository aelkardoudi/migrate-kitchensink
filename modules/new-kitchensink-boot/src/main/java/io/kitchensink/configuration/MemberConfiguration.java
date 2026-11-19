package io.kitchensink.configuration;

import io.kitchensink.member.application.MemberService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(MemberService.class)
public class MemberConfiguration {
}
