package io.kitchensink.adapter.out.h2;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;

@Profile("!mongo")
public interface H2MemberRepository extends JpaRepository<MemberEntity, Long> {
    List<MemberEntity> findAllByOrderByNameAsc();

    boolean existsByEmail(String email);
}
