package com.ruoyi.takeout.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.takeout.domain.TakeoutAddress;
import com.ruoyi.takeout.mapper.TakeoutAddressMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 收货地址簿。
 *
 * 两条不变量（不满足会导致结算页拿不到地址）：
 * 1. 每个用户有地址时，必然恰好有一条 is_default = '1'
 * 2. 每条地址只能被它的 user_id 读写
 *
 * 所以增、改、删之后都要调 ensureDefault()。
 */
@Service
public class TakeoutAddressService {

    /** 单个用户最多保存的地址数，防刷。 */
    private static final int MAX_ADDRESS = 20;

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    @Autowired
    private TakeoutAddressMapper addressMapper;

    public List<TakeoutAddress> list(Long userId) {
        return addressMapper.selectByUser(userId);
    }

    public TakeoutAddress detail(Long addressId, Long userId) {
        TakeoutAddress address = addressMapper.selectById(addressId, userId);
        if (address == null) {
            throw new ServiceException("地址不存在");
        }
        return address;
    }

    public TakeoutAddress defaultAddress(Long userId) {
        return addressMapper.selectDefault(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long add(Long userId, TakeoutAddress body) {
        validate(body);
        int count = addressMapper.countByUser(userId);
        if (count >= MAX_ADDRESS) {
            throw new ServiceException("最多保存 " + MAX_ADDRESS + " 个收货地址，请先删除不用的");
        }

        body.setUserId(userId);
        // 第一条地址强制设为默认，否则结算页取不到默认地址
        boolean asDefault = count == 0 || "1".equals(body.getIsDefault());
        body.setIsDefault(asDefault ? "1" : "0");
        if (asDefault) {
            addressMapper.clearDefault(userId);
        }
        addressMapper.insert(body);
        return body.getAddressId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, TakeoutAddress body) {
        if (body.getAddressId() == null) {
            throw new ServiceException("地址ID不能为空");
        }
        validate(body);
        body.setUserId(userId);
        if ("1".equals(body.getIsDefault())) {
            addressMapper.clearDefault(userId);
        }
        int rows = addressMapper.update(body);
        if (rows == 0) {
            throw new ServiceException("地址不存在");
        }
        // 把原默认地址改成非默认后，可能一条默认都不剩，兜底补一条
        ensureDefault(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long addressId, Long userId) {
        TakeoutAddress address = addressMapper.selectById(addressId, userId);
        if (address == null) {
            throw new ServiceException("地址不存在");
        }
        addressMapper.delete(addressId, userId);
        if ("1".equals(address.getIsDefault())) {
            ensureDefault(userId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long addressId, Long userId) {
        TakeoutAddress address = addressMapper.selectById(addressId, userId);
        if (address == null) {
            throw new ServiceException("地址不存在");
        }
        addressMapper.clearDefault(userId);
        addressMapper.setDefault(addressId, userId);
    }

    /** 没有任何默认地址时，把最近更新的一条顶上。 */
    private void ensureDefault(Long userId) {
        if (addressMapper.selectDefault(userId) != null) {
            return;
        }
        TakeoutAddress latest = addressMapper.selectLatest(userId);
        if (latest != null) {
            addressMapper.setDefault(latest.getAddressId(), userId);
        }
    }

    /**
     * 校验规则与客户端 D5 完全一致。客户端拦一遍是为了体验，这里拦是为了正确性，
     * 两边都不能省。
     */
    private void validate(TakeoutAddress body) {
        String receiver = trim(body.getReceiver());
        if (receiver == null || receiver.length() < 2 || receiver.length() > 20) {
            throw new ServiceException("请填写收货人（2-20 字）");
        }
        body.setReceiver(receiver);

        String phone = trim(body.getPhone());
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new ServiceException("请填写正确的手机号");
        }
        body.setPhone(phone);

        body.setProvince(requireRegion(body.getProvince(), "省"));
        body.setCity(requireRegion(body.getCity(), "市"));
        body.setDistrict(requireRegion(body.getDistrict(), "区/县"));

        String detail = trim(body.getDetail());
        if (detail == null || detail.length() < 5 || detail.length() > 128) {
            throw new ServiceException("详细地址至少 5 个字，最多 128 字");
        }
        body.setDetail(detail);

        String tag = trim(body.getTag());
        if (tag != null && tag.length() > 8) {
            throw new ServiceException("地址标签最多 8 个字");
        }
        body.setTag(tag);
    }

    private String requireRegion(String value, String label) {
        String trimmed = trim(value);
        if (trimmed == null || trimmed.length() < 2 || trimmed.length() > 32) {
            throw new ServiceException("请填写所在" + label);
        }
        return trimmed;
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
