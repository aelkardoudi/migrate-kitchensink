package io.kitchensink.adapter.out.mongo;

import io.kitchensink.member.domain.Member;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("members")
public record MemberDocument(@Id Long id, String name, String email, String phoneNumber) {
    Member toMember() {
        return new Member(id, name, email, phoneNumber);
    }
}
