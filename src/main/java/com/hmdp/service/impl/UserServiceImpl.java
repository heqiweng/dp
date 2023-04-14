package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author steven
 * @since 2023-04-14
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合返回错误信息
            return Result.fail("手机号格式错误");

        }
        //3.符合 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到redis中    //set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("短信验证码发送成功，验证码：{}",code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //1.验证手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }
        //2.验证验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)){
            //3.不一致，报错
            return Result.fail("验证码错误！");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5.判断用户是否存在
        if (user == null){
            //6.不存在，创建用户到并保存
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到redis
        //7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2 将User对象转化为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        //7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //8.返回token
        return Result.ok(token);
    }

//    @Override
//    public Result logout(HttpSession session) {
//
//    }

    @Override
    public Result sing() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        //5.写入redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取档当前用户
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //拼接key
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取从0到dayOfMonth的签到结果
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        if (result == null || result.isEmpty()){
            //没有签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        //遍历循环
        while (true){
            //让这个数字与1做与运算，得到的数字的最后一位bit为 判断这个bit位是否为0
            //num & 1 做与运算，其中1的左边以0补齐
            if ((num & 1) == 0){
                //如果为0，说明未签到，结束
                break;
            }else {
                //如果不为0，说明已签到，计数器 +1
                count++;
            }
            //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            //>>:右移 最高位是0，左边补齐；最高位是1，左边继续补齐
            //<<:左移：左边最高位丢弃，右边补齐0
            //>>>:无符号右移 无论最高位是0还是1，左边补齐0
            //num >>>= 1 ——> num = num >>> 1
            num >>>= 1;
        }
        return Result.ok(count);
    }


    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(5));
        //2.保存用户
        save(user);
        return user;
    }
}
