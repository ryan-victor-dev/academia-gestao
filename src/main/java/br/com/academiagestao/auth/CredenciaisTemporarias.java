package br.com.academiagestao.auth;

/** E-mail de login e senha temporária, devolvidos uma única vez para quem gerou a senha. */
public record CredenciaisTemporarias(String email, String senhaTemporaria) {
}
