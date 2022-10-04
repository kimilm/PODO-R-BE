package be.podor.review.service;

import be.podor.member.dto.MemberDto;
import be.podor.member.model.Member;
import be.podor.member.repository.MemberRepository;
import be.podor.member.service.MemberSearchService;
import be.podor.member.validator.MemberValidator;
import be.podor.musical.model.Musical;
import be.podor.musical.repository.MusicalRepository;
import be.podor.musical.validator.MusicalValidator;
import be.podor.review.dto.ReviewDetailResponseDto;
import be.podor.review.dto.ReviewListResponseDto;
import be.podor.review.dto.ReviewLiveResponseDto;
import be.podor.review.dto.ReviewRequestDto;
import be.podor.review.dto.search.SearchDto;
import be.podor.review.model.Review;
import be.podor.review.model.reviewInfo.ScoreEnum;
import be.podor.review.model.reviewfile.ReviewFile;
import be.podor.review.model.tag.ReviewTag;
import be.podor.review.repository.ReviewRepository;
import be.podor.review.repository.ReviewSearchRepository;
import be.podor.review.repository.ReviewTagRepository;
import be.podor.review.validator.ReviewValidator;
import be.podor.reviewheart.repository.ReviewHeartRepository;
import be.podor.security.UserDetailsImpl;
import be.podor.tag.model.Tag;
import be.podor.tag.repository.TagRepository;
import be.podor.theater.model.TheaterSeat;
import be.podor.theater.model.type.GradeType;
import be.podor.theater.repository.TheaterSeatRepository;
import be.podor.theater.validator.TheaterSeatValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewSearchRepository reviewSearchRepository;

    private final ReviewRepository reviewRepository;

    private final MusicalRepository musicalRepository;

    private final TheaterSeatRepository theaterSeatRepository;

    private final TagRepository tagRepository;

    private final MemberRepository memberRepository;

    private final ReviewTagRepository reviewTagRepository;

    private final ReviewHeartRepository reviewHeartRepository;

    private final MemberSearchService memberSearchService;

    // 리뷰 작성
    @Transactional
    public Review createReview(Long musicalId, ReviewRequestDto requestDto) {
        Musical musical = MusicalValidator.validate(musicalRepository, musicalId);

        TheaterSeat theaterSeat = TheaterSeatValidator.validate(theaterSeatRepository, requestDto, musical);

        Set<Tag> tags = findExistTagsOrElseCreate(requestDto);

        Review review = Review.of(theaterSeat, musical, requestDto);

        reviewRepository.save(review);

        List<ReviewFile> reviewFiles = requestDto.getImgUrls().stream()
                .map(path -> ReviewFile.of(path, review))
                .collect(Collectors.toList());

        List<ReviewTag> reviewTags = tags.stream()
                .map(tag -> ReviewTag.of(review, tag))
                .collect(Collectors.toList());

        tags.forEach(tag -> tag.addReviewTags(reviewTags));

        review.addFiles(reviewFiles);
        review.addTags(reviewTags);

        return review;
    }

    // 최근 리뷰 가져오기 for live
    public List<ReviewLiveResponseDto> getRecentReviews(PageRequest pageRequest) {
        List<Review> reviews = reviewRepository.findTop10ByOrderByCreatedAtDesc();

        return reviews.stream()
                .map(ReviewLiveResponseDto::of)
                .collect(Collectors.toList());
    }

    // 뮤지컬 선택시 해당 뮤지컬의 전체 리뷰 리스트 조회
    public Page<ReviewListResponseDto> getMusicalReviews(
            Long musicalId, SearchDto searchDto, Pageable pageable, UserDetailsImpl userDetails
    ) {
        Page<Review> reviews = reviewSearchRepository.findReviewSearch(musicalId, searchDto, pageable);

        List<ReviewListResponseDto> reviewListResponseDtos = reviews.stream()
                .map(review -> {
                    Boolean heartChecked = userDetails != null && reviewHeartRepository.existsByReviewAndCreatedBy(review, userDetails.getMemberId());
                    return ReviewListResponseDto.of(review, heartChecked);
                })
                .collect(Collectors.toList());

        return new PageImpl<>(reviewListResponseDtos, reviews.getPageable(), reviews.getTotalElements());
    }

    // 리뷰 상세 조회
    public ReviewDetailResponseDto getReviewDetail(Long musicalId, Long reviewId, UserDetailsImpl userDetails) {
        Review review = ReviewValidator.validate(reviewRepository, reviewId);

        Member member = MemberValidator.validate(memberRepository, review.getCreatedBy());

        Boolean heartChecked = userDetails != null && reviewHeartRepository.existsByReviewAndCreatedBy(review, userDetails.getMemberId());

        return ReviewDetailResponseDto.of(review, MemberDto.of(member), heartChecked);
    }

    // 리뷰 수정
    @Transactional
    public ReviewDetailResponseDto updateReview(Long musicalId, Long reviewId, ReviewRequestDto requestDto, UserDetailsImpl userDetails) {
        Review review = ReviewValidator.validate(reviewRepository, reviewId);

        if (!review.getCreatedBy().equals(userDetails.getMemberId())) {
            throw new IllegalArgumentException("다른 사용자의 리뷰를 수정할 수 없습니다.");
        }

        Musical musical = MusicalValidator.validate(musicalRepository, musicalId);

        TheaterSeat theaterSeat = TheaterSeatValidator.validate(theaterSeatRepository, requestDto, musical);

        Set<Tag> tags = findExistTagsOrElseCreate(requestDto);

        review.update(theaterSeat, musical, requestDto);

        List<ReviewFile> reviewFiles = requestDto.getImgUrls().stream()
                .map(path -> ReviewFile.of(path, review))
                .collect(Collectors.toList());

        List<ReviewTag> reviewTags = tags.stream()
                .map(tag -> ReviewTag.of(review, tag))
                .collect(Collectors.toList());

        review.addFiles(reviewFiles);

        // OneToMany ManyToOne 관계 정리
        List<ReviewTag> prevTags = new ArrayList<>(review.getReviewTags());
        review.addTags(reviewTags);
        reviewTagRepository.deleteAllInBatch(prevTags);

        Member member = MemberValidator.validate(memberRepository, review.getCreatedBy());

        Boolean heartChecked = reviewHeartRepository.existsByReviewAndCreatedBy(review, userDetails.getMemberId());

        return ReviewDetailResponseDto.of(review, MemberDto.of(member), heartChecked);
    }

    // 태그 가져오기
    public Set<Tag> findExistTagsOrElseCreate(ReviewRequestDto requestDto) {
        List<String> splitTags = Arrays.asList(requestDto.getTags().split(",\\s*"));

        Set<Tag> existTags = tagRepository.findByTagIn(splitTags);

        Set<String> existTagNames = existTags.stream()
                .map(Tag::getTag)
                .collect(Collectors.toSet());

        for (String splitTag : splitTags) {
            splitTag = splitTag.trim();

            if (splitTag.isEmpty()) {
                continue;
            }

            if (!existTagNames.contains(splitTag)) {
                existTags.add(tagRepository.save(new Tag(splitTag)));
            }
        }

        return existTags;
    }

    // 리뷰 삭제
    @Transactional
    public void deleteReview(Long reviewId, UserDetailsImpl userDetails) {
        reviewRepository.deleteByReviewIdAndCreatedBy(reviewId, userDetails.getMemberId());
    }

    public String[] MusicalNumbers = new String[]{
            "1926년 8월 4일 새벽 4시",
            "관부연락선 도쿠주마루",
            "한 남자와 한 여자가 바다로 몸을 던진다",
            "캄캄한 어둠, 적막한 바다",
            "금지된 사랑 금지된 낭만",
            "허락되지 않은 이야기",
            "아름답지 않은 결말",
            "사라진 한 남자",
            "사라진 한 여자",
            "난 이 모든 일의 목격자",
            "말할 수 없는 비밀",
            "사라져라 비밀이 되어라",
            "찬미하라 비극의 결말을",
            "진실은 바닷 속에 감춰라",
            "그 누구도 알 수 없는",
            "이 죽음의 비밀",
            "사라져라 비밀이 되어라",
            "찬미하라 비극의 결말을",
            "진실은 바닷 속에 감춰라",
            "그 누구도 알 수 없는",
            "이 죽음의 비밀",
            "나 김우진은 오늘 밤, 사랑하는 연인 윤심덕과 함께",
            "이 세상에서 사라지려 한다",
            "불가피한 선택 유일한 길",
            "오늘 밤이 지나면 나는 없다",
            "옭아매는 사슬 막다른 길",
            "오늘 밤이 지나면 나는 자유",
            "슬픔도 눈물도 없는 세상에서",
            "우리 다시 만나리",
            "오해도 편견도 없는 세상에서",
            "나는 노래하리라",
            "우진\n안 돼! 우린 오늘 밤 이 배를 타야 해",
            "심덕\n어째서? 넌 돌아갈 곳이 있지만 난 그렇지 않아",
            "우진\n나도 마찬가지야. 모든 걸 다 버리고 떠나왔어.\n가정도 지위도 재산도 신분도!",
            "심덕\n사랑도... 넌 날 버렸어.\n내가 얼마나 비참했는지\n내가 얼마나 널 의지했는지\n알면서도 넌 날 버렸어\n또 그렇게 떠나가겠지\n또 말 없이 사라지겠지\n내 운명을 너에게 맡길 수 없어",
            "우진\n날 미워해도 좋아\n날 저주해도 좋아\n하지만 오늘 밤만은 날 믿어야 해 제발",
            "심덕\n널 증오해\n네가 죽어버렸으면 좋겠어",
            "우진\n하지만 오늘 밤만은 날 믿어야 해\n제발 제발\n다시는 널 떠나지 않을게\n다시는 도망치지 않을게\n무슨 일이 있어도 네 곁에 있을게",
            "심덕\n난 너를 사랑하지 않아\n난 너를 추억하지도 않아\n하지만 한 가지 약속해\n날 데려가 줘\n그 어디로든\n약속해",
            "우진\n약속할게.",
            "심덕\n약속해",
            "우진\n약속할게.",
            "심덕\n아무도 날 찾지 않는 곳\n아무도 알아보지 않는 곳\n아무도 오해하지 않는 곳\n이 세상엔 없는 곳",
            "(뱃소리)",
            "심덕\n내일이 올까?",
            "우진\n내일은 올 거야.",
            "심덕\n그 어떤 편견도 없는 곳",
            "우진\n그 어떤 편견도 없는 곳",
            "심덕\n그 어떤 경계도 없는 곳",
            "우진\n그 어떤 경계도 없는 곳",
            "심덕\n그 어떤 슬픔도 없는 곳",
            "우진\n슬픔 없는 곳",
            "심덕\n이 세상엔",
            "심덕, 우진\n이 세상엔 없는 곳",
            "아무도 날 찾지 않는 곳\n아무도 알아보지 않는 곳\n아무도 오해하지 않는 곳\n이 세상엔 없는 곳\n이 세상엔 없는 곳",
            "사내\n좋아, 그럼 우선 등장 인물을 정해.\n사색적이고 내성적인 한 남자와 이지적이고 자유분방한 한 여자\n그 둘은 숨 막히도록 고루한 나라 조선에서 태어나\n그리고 각자의 꿈을 이루기 위해서 도쿄로 건너가지.",
            "심덕\n모든 게 달라 저 햇살마저\n눈부신 자유 빛나는 평등\n사랑과 낭만을 부르짖는 곳\n여기는 도쿄",
            "사내\n도쿄는 격변의 시기를 맞이하고 있어.\n전통과 인습은 무너지고, 자유와 일탈을 존중하는 세상이 되어버렸지.\n쏟아져 들어오는 서구의 사상과 철학에 젊은이들은 열광해.\n칸토, 쇼펜하우어, 니체, 마르크스-",
            "우진\n셰익스피어, 빅토르 위고, 톨스토이.",
            "사내\n고전주의, 낭만주의.",
            "우진\n자연주의, 표현주의.",
            "사내\n그들에게 도쿄는 그야말로 새로운 세상이야.\n자, 이 연극은 1921년 도쿄에서 시작해.",
            "심덕\n시를 짓는 사람 토론하는 사람\n춤을 추는 사람 꿈을 꾸는 사람\n저마다 개성을 노래하는 곳\n여기는 도쿄",
            "난 고향의 진부함에 지쳤어\n이 도시의 새로움에 젖었어\n다시 돌아갈 수 있을까\n내일은 알 수 없지만\n좋은 건 좋은 거니까",
            "남자와 여자 노인과 소인\n빈부와 귀천 구별이 없는\n모두가 인간이라 부르짖는 곳\n여기는 도쿄",
            "사내\n남자는 시인을 꿈꾸고, 여자는 가수가 되고 싶어해\n둘은 첫눈에 사랑에 빠지고 뗄레야 뗄 수 없는 사이가 되지\n어떤 운명이 다가오는지도 모르는 채.",
            "여자는 가난해. 부자들의 세계를 동경하지.\n재능도 있고, 야심도 있지만, 가난의 계급을 쉽게 벗어나지 못해.\n남자는 부르주아야. 하지만 그는 그 사실을 부끄러워하지.\n다른 지식인들처럼 빼앗긴 조국을 위해 헌신하지 못하고\n부친의 가업을 이어야 하는 자신의 처지가 괴로워.",
            "둘은 진취적이고 많은 이상을 가졌지만\n그와 전혀 다른 현실에 절망하고 좌절해\n점점 타성에 젖고 회의적으로 변해가.\n그리고 그 둘은...",
            "우진\n그 둘은?",
            "사내\n어떻게 됐을까?",
            "우진 \n어... 비극적인 결말은 아니었으면 좋겠어.",
            "사내\n차차 생각해보자!",
            "우진\n모든 게 달라 저 햇살마저\n눈부신 자유 빛나는 평등\n사랑과 낭만을 부르짖는 곳\n여기는 도쿄"
    };

    @Transactional
    public void insertReview() {
        Random random = new Random(System.currentTimeMillis());

        List<Member> members = memberRepository.findAll(PageRequest.of(0, 16)).getContent();

        List<TheaterSeat> theaterSeats = theaterSeatRepository.findByTheater_TheaterId(5L);

        String[] imgUrls = new String[]{
                "https://img2.sbs.co.kr/img/sbs_cms/WE/2018/11/05/WE98674737_ori.jpg",
                "https://img.seoul.co.kr/img/upload/2017/09/10/SSI_20170910162637.jpg",
                "https://www.artinsight.co.kr/data/tmp/2003/20200307233950_msepktyn.jpg",
                "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT-eNwFxShxNWQULBw6aVc2tpZoEg9P8hUKgOU-jEFfMR86XRAbnvy5DeW0HcD9jcRVoEM&usqp=CAU",
                "http://image.yes24.com/images/chyes24/c/c/9/c/cc9c2aed49a11061b4fe8f8986d1022f.jpg",
                "https://i.pinimg.com/originals/b6/0a/83/b60a83a95d03ca1c77fa1d22b85cffac.jpg",
                "https://t1.daumcdn.net/cfile/tistory/99C2A04F5D3E29831D",
                "https://d3kb3kbkoieiep.cloudfront.net/upFiles/StageTalkV2/Editor/2015/07/20150714_185143401_19065.jpg",
                "https://static.hubzum.zumst.com/hubzum/2019/08/22/09/fe535b25a96744eb8c7934e460d47a53.jpg",
                "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTNWqcVCHUdDVLGpBT_Kc6bxN-21aztWyrMmA&usqp=CAU",
                "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQHLtnmDpgcE4VKFBzjx6mhpm2J8utiXTOHSstodhu-xCGMth6qV65nJdlLZ4fZCAllFXM&usqp=CAU",
                "https://image.yes24.com/themusical/upFiles/StageTalkV2/Magazine/20191105/20191105022803c8b2442bd8f247a8913c9d337b13508d.png",
                "https://cdn.mhns.co.kr/news/photo/201506/6345_14427_423.jpg",
                "https://file.mk.co.kr/meet/neds/2015/08/image__2015_793330_14398770502078848.jpg",
                "https://file.mk.co.kr/meet/neds/2015/08/image_readtop_2015_793330_14398770502078843.jpg"
        };

        String[] tagRand = new String[]{
                "1920년대",
                "극작가로",
                "한국",
                "연극의",
                "개척자인",
                "김우진",
                "조선",
                "최초의",
                "소프라노",
                "윤심덕",
                "1926년_8월_4일",
                "현해탄_실종사건",
                "실화에",
                "허구를",
                "넣어",
                "각색한",
                "창작",
                "뮤지컬이다",
                "등장인물은",
                "김우진",
                "윤심덕",
                "사내로",
                "총_세_명이_나오는_삼인극",
                "실존인물들",
                "스토리",
                "사내",
                "가공인물",
                "2013년_초연",
                "2014년_재연",
                "글루미데이_라는_제목",
                "2015년_삼연부터",
                "사의찬미로_변경",
                "반주는_라이브",
                "피아노",
                "바이올린",
                "첼로",
                "현악_라이브_삼중주",
                "무대는",
                "우진의_방",
                "배_내부는_아래",
                "위에는_뱃머리와_갑판",
                "무대_왼쪽엔_동그란_발판",
                "2022년_10주년",
                "10주년_기념_공연"
        };

        for (int i = 0; i < 10000; i++) {
            int memberNo = random.nextInt(16);
            Member member = members.get(memberNo);
            UserDetailsImpl userDetails = new UserDetailsImpl(member.getId(), member.getMemberRole().toString());
            Authentication authentication = new UsernamePasswordAuthenticationToken(userDetails, "", userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);

            int content = random.nextInt(MusicalNumbers.length);

            TheaterSeat theaterSeat = theaterSeats.get(random.nextInt(theaterSeats.size()));

            Musical musical = musicalRepository.findById(5L).orElseThrow();

            GradeType grade = GradeType.values()[random.nextInt(GradeType.values().length)];

            ScoreEnum gap = ScoreEnum.values()[random.nextInt(ScoreEnum.values().length)];
            ScoreEnum sight = ScoreEnum.values()[random.nextInt(ScoreEnum.values().length)];
            ScoreEnum sound = ScoreEnum.values()[random.nextInt(ScoreEnum.values().length)];
            ScoreEnum light = ScoreEnum.values()[random.nextInt(ScoreEnum.values().length)];

            String operaGlass = random.nextBoolean() ? "on" : null;
            String block = random.nextBoolean() ? "on" : null;

            List<String> img = new ArrayList<>();
            img.add(imgUrls[random.nextInt(imgUrls.length)]);

            int length = random.nextInt(10);

            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < length; j++) {
                sb.append(tagRand[random.nextInt(tagRand.length)]).append(", ");
            }

            ReviewRequestDto requestDto = new ReviewRequestDto(
                    grade,//private GradeType grade;
                    theaterSeat.getFloor(),//private FloorType floor;
                    theaterSeat.getSection(),//private String section;
                    theaterSeat.getSeatRow(),//private String row;
                    theaterSeat.getSeat(),//private Integer seat;
                    MusicalNumbers[content], //private String reviewContent;
                    img,//private List<String> imgUrls;
                    gap,//private ScoreEnum gap;
                    sight,//private ScoreEnum sight;
                    sound,//private ScoreEnum sound;
                    light,//private ScoreEnum light;
                    operaGlass,//private String operaGlass;
                    block,//private String block;
                    sb.toString()//private String tags;
            );

            Review review = Review.of(theaterSeat, musical, requestDto);

            Set<Tag> tags = findExistTagsOrElseCreate(requestDto);

            reviewRepository.save(review);

            List<ReviewFile> reviewFiles = requestDto.getImgUrls().stream()
                    .map(path -> ReviewFile.of(path, review))
                    .collect(Collectors.toList());

            List<ReviewTag> reviewTags = tags.stream()
                    .map(tag -> ReviewTag.of(review, tag))
                    .collect(Collectors.toList());

            tags.forEach(tag -> tag.addReviewTags(reviewTags));

            review.addFiles(reviewFiles);
            review.addTags(reviewTags);
        }
    }
}
