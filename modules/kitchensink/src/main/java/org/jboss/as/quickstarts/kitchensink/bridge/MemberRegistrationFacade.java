package org.jboss.as.quickstarts.kitchensink.bridge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.jboss.as.quickstarts.kitchensink.model.Member;

@ApplicationScoped
public class MemberRegistrationFacade {

    @Inject
    private MemberApiClient memberApi;

    @Inject
    private Event<Member> memberRegistered;

    public void register(Member member) {
        memberApi.register(member);
        memberRegistered.fire(member);
    }
}
