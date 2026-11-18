package org.jboss.as.quickstarts.kitchensink.bridge;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;

@ApplicationScoped
public class MemberReadFacade {

    @Inject
    private MemberRepository repository;

    public List<Member> findAllOrderedByName() {
        return repository.findAllOrderedByName();
    }

    public Member findById(Long id) {
        return repository.findById(id);
    }
}
