package com.mawai.wiibsim.dto;

import lombok.Data;

/** Google userinfo 接口返回的用户信息（scope=openid profile）；name、picture 不保证有 */
@Data
public class GoogleUserInfo {

    /** Google 账号的唯一标识，不会复用 */
    private String sub;

    private String name;

    private String picture;
}
