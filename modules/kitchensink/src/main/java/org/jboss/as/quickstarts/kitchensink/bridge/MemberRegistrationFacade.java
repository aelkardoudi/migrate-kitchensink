package org.jboss.as.quickstarts.kitchensink.bridge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.service.MemberRegistration;

@ApplicationScoped
public class MemberRegistrationFacade {

    @Inject
    private MemberRepository repository;

    @Inject
    private MemberRegistration registration;

    public boolean emailAlreadyExists(String email) {
        try {
            return repository.findByEmail(email) != null;
        } catch (NoResultException e) {
            return false;
        }
    }

    public void register(Member member) throws Exception {
        registration.register(member);
    }
}
