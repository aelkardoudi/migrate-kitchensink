package org.jboss.as.quickstarts.kitchensink.bridge;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.as.quickstarts.kitchensink.model.Member;

@ApplicationScoped
public class MemberReadFacade {

    @Inject
    private MemberApiClient memberApi;

    public List<Member> findAllOrderedByName() {
        return memberApi.findAllOrderedByName();
    }

    public Member findById(Long id) {
        return memberApi.findById(id);
    }
}
