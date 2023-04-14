package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author steven
 * @since 2021-04-14
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sing();

    Result signCount();

//    Result logout(HttpSession session);
}
