package io.kitchensink.member.domain;

public class DuplicateMemberEmailException extends RuntimeException {
    public DuplicateMemberEmailException() {
        super("Email taken");
    }
}
