package be.podor.member.service;

import be.podor.member.model.Member;
import be.podor.security.UserDetailsImpl;
import be.podor.security.jwt.JwtTokenProvider;
import be.podor.security.jwt.TokenDto;
import be.podor.security.jwt.refresh.RefreshToken;
import be.podor.security.jwt.refresh.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;

@RequiredArgsConstructor
@Service
public class MemberService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public void logout(UserDetailsImpl userDetails) {
        refreshTokenRepository.deleteByMember_Id(userDetails.getMemberId());
    }

    public TokenDto saveToken(Member member) {

        TokenDto tokenDto = jwtTokenProvider.createToken(member);

        RefreshToken refreshTokenObject = RefreshToken.builder()
                .id(member.getId())
                .member(member)
                .tokenValue(tokenDto.getRefreshToken())
                .build();

        refreshTokenRepository.save(refreshTokenObject);

        return tokenDto;
    }
}
