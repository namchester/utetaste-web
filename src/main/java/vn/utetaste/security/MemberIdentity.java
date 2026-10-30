package vn.utetaste.security;
/** Created only after validating UT_ACCESS; never bound from request JSON or form fields. */
public record MemberIdentity(Long id,String name,int version) {}
