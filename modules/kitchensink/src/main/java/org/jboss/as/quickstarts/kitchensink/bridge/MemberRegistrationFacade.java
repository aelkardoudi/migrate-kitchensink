package org.jboss.as.quickstarts.kitchensink.bridge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.as.quickstarts.kitchensink.model.Member;

@ApplicationScoped
public class MemberRegistrationFacade {

    @Inject
    private MemberApiClient memberApi;

    public void register(Member member) {
        memberApi.register(member);
    }
}
