package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author steven
 * @since 2023-04-03
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);

    Result queryShopType(Integer typeId, Integer current, Double x, Double y);
}
