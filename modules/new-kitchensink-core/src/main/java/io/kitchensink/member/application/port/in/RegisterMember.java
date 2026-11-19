package io.kitchensink.member.application.port.in;

public interface RegisterMember {
    void register(String name, String email, String phoneNumber);
}
