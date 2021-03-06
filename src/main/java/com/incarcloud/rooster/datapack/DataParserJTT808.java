package com.incarcloud.rooster.datapack;

import com.incarcloud.rooster.util.JTT808DataPackUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import javax.xml.bind.DatatypeConverter;
import java.util.*;

/**
 * JTT808 Parser.
 *
 * @author Aaric, created on 2017-08-22T10:22.
 * @since 2.0
 */
public class DataParserJTT808 implements IDataParser {

    /**
     * 协议分组和名称
     */
    public static final String PROTOCOL_GROUP = "china";
    public static final String PROTOCOL_NAME = "jtt808";
    public static final String PROTOCOL_VERSION = "2013.1";
    public static final String PROTOCOL_PREFIX = PROTOCOL_GROUP + "-" + PROTOCOL_NAME + "-";

    static {
        /**
         * 声明数据包版本与解析器类关系
         */
        DataParserManager.register(PROTOCOL_PREFIX + PROTOCOL_VERSION, DataParserJTT808.class);
    }

    /**
     * 数据包准许最大容量2M
     */
    private static final int DISCARDS_MAX_LENGTH = 1024 * 1024 * 2;

    @Override
    public List<DataPack> extract(ByteBuf buffer) {
        /**
         * ## JTT808数据包格式 ###
         * # 1.标识位(0x7E)
         * # 2.消息头
         * # 3.消息体
         * # 4.检验码
         * # 5.标识位(0x7E)
         */
        DataPack dataPack;
        List<DataPack> dataPackList = new ArrayList<>();

        // 长度大于2M的数据包直接抛弃(恶意数据)
        if (DISCARDS_MAX_LENGTH < buffer.readableBytes()) {
            //System.out.println("clear");
            buffer.clear();
        }

        // 遍历
        byte check;
        int start, offset;
        List<Byte> byteList;
        while (buffer.isReadable()) {
            // 初始化
            start = buffer.readerIndex();
            offset = start + 1;

            // 寻找以0x7E开始和0x7E结束的数据段
            if (0x7E == (buffer.getByte(start) & 0xFF)) {
                // 寻找0x7E结束点
                for (; offset < buffer.writerIndex(); offset++) {
                    if (0x7E == (buffer.getByte(offset) & 0xFF)) {
                        // 寻找0x7E结束点成功，结束for循环
                        break;
                    }
                }

                // 寻找0x7E结束点失败，结束while循环
                if (buffer.writableBytes() == offset) {
                    break;
                }

                // 转义还原字节码
                // 还原规则：0x7D0x01->0x7D, 0x7D0x02->0x7E
                byteList = new ArrayList<>();
                for (int i = start + 1; i < offset - 1; i++) {
                    if (0x7D == (buffer.getByte(i) & 0xFF) && 0x01 == (buffer.getByte(i + 1) & 0xFF)) {
                        // 0x7D0x01->0x7D
                        byteList.add((byte) 0x7D);
                        i++;
                    } else if (0x7D == (buffer.getByte(i) & 0xFF) && 0x02 == (buffer.getByte(i + 1) & 0xFF)) {
                        // 0x7D0x02->0x7E
                        byteList.add((byte) 0x7E);
                        i++;
                    } else {
                        byteList.add(buffer.getByte(i));
                    }
                }

                // 计算校验码
                check = 0x00;
                for (Byte byteObject : byteList) {
                    // 校验码指从消息头开始，同后一字节异或，直到校验码前一个字节
                    check ^= byteObject.byteValue();
                }

                // 验证校验码
                if (buffer.getByte(offset - 1) == check) {
                    //System.out.println("oxr check success");
                    //System.out.println(String.format("%d-%d", start, offset - start + 1));
                    // 打包
                    dataPack = new DataPack(PROTOCOL_GROUP, PROTOCOL_NAME, PROTOCOL_VERSION);
                    dataPack.setBuf(buffer.slice(start, offset - start + 1));
                    dataPackList.add(dataPack);
                }

                // 跳跃(offset - start + 1)个字节
                buffer.skipBytes(offset - start + 1);

            } else {
                // 不符合条件，向前跳跃1
                buffer.skipBytes(1);
            }
        }

        return dataPackList;
    }

    /**
     * 验证数据包
     *
     * @param bytes 原始数据
     * @return 返回转义还原的字节数组
     */
    private byte[] validate(byte[] bytes) {
        if (null != bytes && 2 < bytes.length) {
            // 标识位(0x7e)
            if (0x7E == (bytes[0] & 0xFF) && 0x7E == (bytes[bytes.length - 1] & 0xFF)) {
                // 转义还原字节码
                // 还原规则：0x7D0x01->0x7D, 0x7D0x02->0x7E
                List<Byte> byteList = new ArrayList<>();
                byteList.add(bytes[0]); // 标识位(0x7E)
                for (int i = 1; i < bytes.length - 1; i++) {
                    if (0x7D == (bytes[i] & 0xFF) && 0x01 == (bytes[i + 1] & 0xFF)) {
                        // 0x7D0x01->0x7D
                        byteList.add((byte) 0x7D);
                        i++;
                    } else if (0x7D == (bytes[i] & 0xFF) && 0x02 == (bytes[i + 1] & 0xFF)) {
                        // 0x7D0x02->0x7E
                        byteList.add((byte) 0x7E);
                        i++;
                    } else {
                        byteList.add(bytes[i]);
                    }
                }
                byteList.add(bytes[bytes.length - 1]); // 标识位(0x7E)

                // List<Byte> --> byte[]
                byte[] shiftBytes = new byte[byteList.size()];
                for (int i = 0; i < shiftBytes.length; i++) {
                    shiftBytes[i] = byteList.get(i).byteValue();
                }

                // 计算校验码
                byte check = 0x00;
                for (int i = 1; i <= shiftBytes.length - 3; i++) {
                    check ^= shiftBytes[i];
                }

                // 验证校验码
                if (shiftBytes[shiftBytes.length - 2] == check) {
                    return shiftBytes;
                }
            }
        }
        return null;
    }

    @Override
    public ByteBuf createResponse(DataPack requestPack, ERespReason reason) {
        // 发送消息时：消息封装——>计算并填充校验码——>转义
        // 0x7e-0x7d02, 0x7d-0x7d01
        if (null != requestPack && null != reason) {
            // 原始数据
            byte[] dataPackBytes = validate(Base64.getDecoder().decode(requestPack.getDataB64()));
            if (null != dataPackBytes) {
                // 初始化List容器，装载【消息头+消息体】
                List<Byte> byteList = new ArrayList<>();

                // 预留回复命令字位置
                byteList.add((byte) 0xFF);
                byteList.add((byte) 0xFF);

                // 预留消息长度位置
                byteList.add((byte) 0xFF);
                byteList.add((byte) 0xFF);

                // 设置终端手机号(6个字节BCD码)，5~10
                for (int i = 5; i <= 10; i++) {
                    byteList.add(dataPackBytes[i]);
                }

                // 设置消息流水号，同终端消息的流水号
                byteList.add(dataPackBytes[11]);
                byteList.add(dataPackBytes[12]);
                /*====================begin-判断msgId回复消息-begin====================*/
                // 消息ID
                int msgId = ((dataPackBytes[1] & 0xFF) << 8) | (dataPackBytes[2] & 0xFF);
                int msgLength;
                byte statusCode;

                // 根据msgId回复信息，否则使用通用应答
                switch (msgId) {
                    case 0x0100:
                        // 0x0100 - 终端注册
                        // 0x8100 - 终端注册应答
                        byteList.set(0, (byte) 0x81);
                        byteList.set(1, (byte) 0x00);

                        // 设置对应的终端消息的流水号
                        byteList.add(dataPackBytes[11]);
                        byteList.add(dataPackBytes[12]);

                        // 设置结果
                        // 结果说明：0：成功；1：车辆已被注册；2：数据库中无该车辆；3：终端已被注册；4：数据库中无该终端
                        switch (reason) {
                            case OK:
                                // 成功接收
                                statusCode = 0x00;
                                //--add
                                byteList.add(statusCode);
                                // 鉴权码：使用UUID策略
                                byte[] authCodeBytes = UUID.randomUUID().toString().getBytes();
                                for (int i = 0; i < authCodeBytes.length; i++) {
                                    byteList.add(authCodeBytes[i]);
                                }
                                // 消息长度
                                msgLength = 3 + authCodeBytes.length & 0x01FF;
                                break;
                            default:
                                // 其他
                                statusCode = 0x01;
                                // 消息长度：3个字节
                                msgLength = 3 & 0x01FF;
                                //--add
                                byteList.add(statusCode);
                        }

                        break;
                    case 0x0801:
                        // 0x0801 - 多媒体数据上传
                        // 0x8800 - 多媒体数据上传应答
                        byteList.set(0, (byte) 0x81);
                        byteList.set(1, (byte) 0x00);

                        // 设置多媒体ID
                        byteList.add(dataPackBytes[13]);
                        byteList.add(dataPackBytes[14]);

                        // 设置重传包总数
                        byteList.add((byte) 0x00);

                        // 设置重传包ID列表（默认不要求重传）
                        // 0x00 -> 无
                    default:
                        // 0x8001 - 平台通用应答
                        byteList.set(0, (byte) 0x80);
                        byteList.set(1, (byte) 0x01);

                        // 设置对应的终端消息的流水号
                        byteList.add(dataPackBytes[11]);
                        byteList.add(dataPackBytes[12]);

                        // 设置对应的终端消息的ID
                        byteList.add(dataPackBytes[1]);
                        byteList.add(dataPackBytes[2]);

                        // 设置结果
                        // 结果说明：0：成功/确认；1：失败；2：消息有误；3：不支持；4：报警处理确认；
                        switch (reason) {
                            case OK:
                                // 成功接收
                                statusCode = 0x00;
                                break;
                            default:
                                // 其他
                                statusCode = 0x01;
                        }
                        byteList.add(statusCode);

                        // 消息长度：平台通用应答回复5个字节：【应答流水号】+【应答 ID】+【结果】
                        msgLength = 5 & 0x01FF;
                }

                // 设置消息体属性
                // 双字节，最后9个bit表示消息长度，所以&0xFE00在&0x05
                int msgProps = (((dataPackBytes[3] & 0xFF) << 8) & (dataPackBytes[4] & 0xFF) & 0xFE00) | msgLength;
                // 回复数据不分包(位13-分包：0-不分包；1-分包)
                msgProps = msgProps & 0xDFFF;
                // 设置长度信息
                byteList.set(2, (byte) ((msgProps >> 8) & 0xFF));
                byteList.set(3, (byte) (msgProps & 0xFF));
                /*====================end---判断msgId回复消息---end====================*/

                // 计算并填充校验码
                byte check = 0x00;
                for (Byte byteObject : byteList) {
                    check ^= byteObject.byteValue();
                }
                byteList.add(check);

                // 转义
                List<Byte> defaultByteList = new ArrayList<>();
                defaultByteList.add((byte) 0x7E); //标识位(0x7E)
                for (Byte byteObject : byteList) {
                    if (0x7D == (byteObject.byteValue() & 0xFF)) {
                        // 0x7D->0x7D0x01
                        defaultByteList.add((byte) 0x7D);
                        defaultByteList.add((byte) 0x01);
                    } else if (0x7E == (byteObject.byteValue() & 0xFF)) {
                        // 0x7E->0x7D0x02
                        defaultByteList.add((byte) 0x7D);
                        defaultByteList.add((byte) 0x02);
                    } else {
                        defaultByteList.add(byteObject);
                    }
                }
                defaultByteList.add((byte) 0x7E); //标识位(0x7E)

                // add to buffer
                byte[] responseBytes = new byte[defaultByteList.size()];
                for (int i = 0; i < responseBytes.length; i++) {
                    responseBytes[i] = defaultByteList.get(i);
                }

                // return
                return Unpooled.wrappedBuffer(responseBytes);
            }
        }
        return null;
    }

    @Override
    public void destroyResponse(ByteBuf responseBuf) {
        if (null != responseBuf) {
            ReferenceCountUtil.release(responseBuf);
        }
    }

    @Override
    public List<DataPackTarget> extractBody(DataPack dataPack) {
        ByteBuf buffer = null;
        List<DataPackTarget> dataPackTargetList = null;
        byte[] dataPackBytes = validate(Base64.getDecoder().decode(dataPack.getDataB64()));
        if (null != dataPackBytes) {
            // 声明变量信息
            dataPackTargetList = new ArrayList<>();
            DataPackObject dataPackObject = new DataPackObject(dataPack);
            DataPackPosition dataPackPosition;
            DataPackAlarm dataPackAlarm;

            try {
                // 初始化ByteBuf
                buffer = Unpooled.wrappedBuffer(dataPackBytes);

                /* 标识位 */
                // 跳过标识位(0x7e)
                buffer.readBytes(1);

                /* 消息头 */
                // 1.消息ID
                int msgId = JTT808DataPackUtil.readWord(buffer);
                JTT808DataPackUtil.debug("msgId: " + msgId);

                // 2.消息体属性
                int msgProps = JTT808DataPackUtil.readWord(buffer);
                // 2.1 消息体长度
                int msgLength = msgProps & 0x01FF;
                JTT808DataPackUtil.debug("msgLength: " + msgLength);
                // 2.2 数据加密方式
                int msgEncryptMode = (msgProps >> 10) & 0x0007;
                JTT808DataPackUtil.debug("msgEncryptMode: " + msgEncryptMode);
                switch (msgEncryptMode) {
                    case 0:
                        // 消息体不加密
                        JTT808DataPackUtil.debug("--消息体不加密");
                        break;
                    case 1:
                        // 第 10 位为 1，表示消息体经过 RSA 算法加密
                        JTT808DataPackUtil.debug("--RSA 算法加密");
                        dataPackObject.setEncryptName("RSA");
                }
                // 2.3 分包
                int msgSubPack = (msgProps >> 13) & 0x0001;
                JTT808DataPackUtil.debug("msgSubPack: " + msgSubPack);

                // 3.终端手机号(设备号)
                String deviceId = JTT808DataPackUtil.readBCD(buffer);
                JTT808DataPackUtil.debug("deviceId: " + deviceId);
                dataPackObject.setDeviceId(deviceId);

                // 4.消息流水号
                int msgSeq = JTT808DataPackUtil.readWord(buffer);
                JTT808DataPackUtil.debug("msgSeq: " + msgSeq);
                dataPackObject.setPackId(msgSeq);

                // 5.消息包封装项
                int msgSubPackTotal = 0;
                int msgSubPackIndex = 0;
                switch (msgSubPack) {
                    case 0:
                        // 第 13 位为 0，则消息头中无消息包封装项字段
                        JTT808DataPackUtil.debug("--无消息包封装项字段");
                        break;
                    case 1:
                        // 第 13 位为 1 时表示消息体为长消息，进行分包发送处理
                        JTT808DataPackUtil.debug("--分包发送处理");
                        // 5.1 消息总包数
                        msgSubPackTotal = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("--msgSubPackTotal: " + msgSubPackTotal);
                        dataPackObject.setSubPackTotal(msgSubPackTotal);
                        // 5.2 包序号
                        msgSubPackIndex = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("--msgSubPackIndex: " + msgSubPackIndex);
                        dataPackObject.setSubPackIndex(msgSubPackIndex);
                        break;
                }

                // 6.检验时间（=当前系统时间）
                dataPackObject.setDetectionTime(Calendar.getInstance().getTime());

                /* 消息体 */
                switch (msgId) {
                    case 0x001:
                        /* 终端通用应答 */
                        System.out.println("## 0x001 - 终端通用应答");
                        break;
                    case 0x0002:
                        /* 终端心跳 */
                        System.out.println("## 0x0002 - 终端心跳");
                        //--心跳数据
                        DataPackHeartbeat dataPackHeartbeat = new DataPackHeartbeat(dataPackObject);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackHeartbeat));
                        break;
                    case 0x0100:
                        /* 终端注册 */
                        System.out.println("## 0x0100 - 终端注册");
                        //--注册数据
                        DataPackRegister dataPackRegister = new DataPackRegister(dataPackObject);
                        // 1.省域 ID
                        int provinceId = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("provinceId: " + provinceId);
                        dataPackRegister.setProvinceId(provinceId);
                        // 2.市县域 ID
                        int cityId = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("cityId: " + cityId);
                        dataPackRegister.setCityId(cityId);
                        // 3.制造商 ID
                        String terminalMakerId = JTT808DataPackUtil.readByteArray(buffer, 5);
                        JTT808DataPackUtil.debug("terminalMakerId: " + terminalMakerId);
                        dataPackRegister.setTerminalMakerId(terminalMakerId);
                        // 4.终端型号
                        String terminalModel = JTT808DataPackUtil.readByteArray(buffer, 20);
                        JTT808DataPackUtil.debug("terminalModel: " + terminalModel);
                        dataPackRegister.setTerminalModel(terminalModel);
                        // 5.终端 ID
                        String terminalId = JTT808DataPackUtil.readByteArray(buffer, 7);
                        JTT808DataPackUtil.debug("terminalId: " + terminalId);
                        dataPackRegister.setTerminalId(terminalId);
                        // 6.车牌颜色（按照 JT/T415-2006 的 5.4.12）
                        int colorId = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("colorId: " + colorId);
                        dataPackRegister.setColorId(colorId);
                        switch (colorId) {
                            case 0:
                                // 未上牌时，取值为 0
                                JTT808DataPackUtil.debug("--未上牌");
                                break;
                            case 1:
                                // 1-蓝色
                                JTT808DataPackUtil.debug("--蓝色");
                                break;
                            case 2:
                                // 2-黄色
                                JTT808DataPackUtil.debug("--黄色");
                                break;
                            case 3:
                                // 3-黑色
                                JTT808DataPackUtil.debug("--黑色");
                                break;
                            case 4:
                                // 4-白色
                                JTT808DataPackUtil.debug("--白色");
                                break;
                            case 9:
                                // 9-其他
                                JTT808DataPackUtil.debug("--其他");
                                break;
                            default:
                                // 车辆颜色
                        }
                        // 7.车辆标识
                        String vin = JTT808DataPackUtil.readString(buffer);
                        if (0 == colorId) {
                            // VIN
                            JTT808DataPackUtil.debug("vin: " + vin);
                            dataPackRegister.setVid(vin);
                        } else {
                            // License
                            JTT808DataPackUtil.debug("License: " + vin);
                            dataPackRegister.setLicense(vin);
                        }
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackRegister));
                        break;
                    case 0x0003:
                        /* 终端注销 */
                        System.out.println("## 0x0003 - 终端注销");
                        // 终端注销消息体为空
                        //--注销数据
                        DataPackRevoke dataPackRevoke = new DataPackRevoke(dataPackObject);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackRevoke));
                        break;
                    case 0x0102:
                        /* 终端鉴权 */
                        System.out.println("## 0x0102 - 终端鉴权");
                        //--鉴权数据
                        DataPackAuthentication dataPackAuthentication = new DataPackAuthentication(dataPackObject);
                        // 1.鉴权码
                        String authCode = JTT808DataPackUtil.readString(buffer);
                        JTT808DataPackUtil.debug("authCode: " + authCode);
                        dataPackAuthentication.setAuthCode(authCode);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackAuthentication));
                        break;
                    case 0x0104:
                        /* 查询终端参数应答 */
                        System.out.println("## 0x0104 - 查询终端参数应答");
                        // 1.应答流水号
                        int responseMsgSeq = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("responseMsgSeq: " + responseMsgSeq);
                        // 2.应答参数个数
                        int paramsTotal = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("paramsTotal: " + paramsTotal);
                        // 3.参数项列表
                        List<DataPackPeak.Peak> peakList = new ArrayList<>();
                        if (0 < paramsTotal) {
                            long paramId;
                            int paramLength;
                            byte[] paramValue;
                            String paramValueString;
                            DataPackPeak.Peak peak;
                            for (int i = 0; i < paramsTotal; i++) {
                                // 3.1 参数 ID
                                paramId = JTT808DataPackUtil.readDWord(buffer);
                                JTT808DataPackUtil.debug("paramId: " + paramId);
                                // 3.2 参数长度
                                paramLength = JTT808DataPackUtil.readByte(buffer);
                                JTT808DataPackUtil.debug("paramLength: " + paramLength);
                                // 3.3 参数值
                                paramValue = JTT808DataPackUtil.readBytes(buffer, paramLength);
                                paramValueString = DatatypeConverter.printHexBinary(paramValue);
                                JTT808DataPackUtil.debug("paramValue: " + paramValueString);
                                // 添加数据记录
                                peak = new DataPackPeak.Peak();
                                peak.setPeakId(new Long(paramId).intValue());
                                // 直接存储字节码
                                // 这种方法不是很好，后面有空改进
                                peak.setPeakValue(paramValueString);
                                peak.setPeakDesc("存储二进制字符串，需要根据“表 11 终端参数项数据格式”转换数据");
                                peakList.add(peak);
                            }
                        }
                        // 封装TARGET数据
                        if (null != peakList && 0 < peakList.size()) {
                            //--极值数据
                            DataPackPeak dataPackPeak = new DataPackPeak(dataPackObject);
                            dataPackPeak.setPeakList(peakList);
                            //--add
                            dataPackTargetList.add(new DataPackTarget(dataPackPeak));
                        }
                        break;
                    case 0x0107:
                        /* 查询终端属性应答 */
                        System.out.println("## 0x0107 - 查询终端属性应答");
                        //--上报设备数据
                        DataPackDevice dataPackDevice = new DataPackDevice(dataPackObject);
                        // 1.终端类型
                        int terminalType = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("terminalType: " + terminalType);
                        dataPackDevice.setTerminalType(terminalType);
                        // 2.制造商 ID
                        terminalMakerId = JTT808DataPackUtil.readByteArray(buffer, 5);
                        JTT808DataPackUtil.debug("terminalMakerId: " + terminalMakerId);
                        dataPackDevice.setTerminalMakerId(terminalMakerId);
                        // 3.终端型号
                        terminalModel = JTT808DataPackUtil.readByteArray(buffer, 20);
                        JTT808DataPackUtil.debug("terminalModel: " + terminalModel);
                        dataPackDevice.setTerminalModel(terminalModel);
                        // 4.终端 ID
                        terminalId = JTT808DataPackUtil.readByteArray(buffer, 7);
                        JTT808DataPackUtil.debug("terminalId: " + terminalId);
                        dataPackDevice.setTerminalId(terminalId);
                        // 5.终端 SIM 卡 ICCID
                        String terminalSIMICCID = JTT808DataPackUtil.readBCD(buffer, 10);
                        JTT808DataPackUtil.debug("terminalSIMICCID: " + terminalSIMICCID);
                        dataPackDevice.setTerminalSIMICCID(terminalSIMICCID);
                        // 6.终端硬件版本号
                        String hardwareVersion = JTT808DataPackUtil.readString(buffer, JTT808DataPackUtil.readByte(buffer));
                        JTT808DataPackUtil.debug("hardwareVersion: " + hardwareVersion);
                        dataPackDevice.setHardwareVersion(hardwareVersion);
                        // 7.终端固件版本号
                        String firmwareVersion = JTT808DataPackUtil.readString(buffer, JTT808DataPackUtil.readByte(buffer));
                        JTT808DataPackUtil.debug("firmwareVersion: " + firmwareVersion);
                        dataPackDevice.setFirmwareVersion(firmwareVersion);
                        // 8.GNSS 模块属性
                        int gnssProps = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("gnssProps: " + gnssProps);
                        // 8.1 支持 GPS 定位
                        if (1 == (gnssProps & 0x01)) {
                            dataPackDevice.setSupportGPS(true);
                        }
                        // 8.2 支持北斗定位
                        if (1 == ((gnssProps >> 1) & 0x01)) {
                            dataPackDevice.setSupportBeidou(true);
                        }
                        // 8.3 支持 GLONASS 定位
                        if (1 == ((gnssProps >> 2) & 0x01)) {
                            dataPackDevice.setSupportGlonass(true);
                        }
                        // 8.4 支持 Galileo 定位
                        if (1 == ((gnssProps >> 3) & 0x01)) {
                            dataPackDevice.setSupportGalileo(true);
                        }
                        // 9.通信模块属性
                        int communicationProps = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("communicationProps: " + communicationProps);
                        // 9.1 支持GPRS通信
                        if (1 == (communicationProps & 0x01)) {
                            dataPackDevice.setSupportGPRS(true);
                        }
                        // 9.2 支持CDMA通信
                        if (1 == ((communicationProps >> 1) & 0x01)) {
                            dataPackDevice.setSupportCMDA(true);
                        }
                        // 9.3 支持TD-SCDMA通信
                        if (1 == ((communicationProps >> 2) & 0x01)) {
                            dataPackDevice.setSupportTDSCDMA(true);
                        }
                        // 9.4 支持WCDMA通信
                        if (1 == ((communicationProps >> 3) & 0x01)) {
                            dataPackDevice.setSupportWCDMA(true);
                        }
                        // 9.5 支持CDMA2000通信
                        if (1 == ((communicationProps >> 4) & 0x01)) {
                            dataPackDevice.setSupportCDMA2000(true);
                        }
                        // 9.6 支持TD-LTE通信
                        if (1 == ((communicationProps >> 5) & 0x01)) {
                            dataPackDevice.setSupportTDLTE(true);
                        }
                        //-add
                        dataPackTargetList.add(new DataPackTarget(dataPackDevice));
                        break;
                    case 0x0108:
                        /* 终端升级结果通知 */
                        System.out.println("## 0x0108 - 终端升级结果通知");
                        //--结果数据
                        DataPackResult dataPackResult = new DataPackResult(dataPackObject);
                        // 1.升级类型：0：终端，12：道路运输证 IC 卡读卡器，52：北斗卫星定位模块
                        int upgradeType = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("upgradeType: " + upgradeType);
                        dataPackResult.setResultType(upgradeType);
                        // 2.升级结果：0：成功，1：失败，2：取消
                        int upgradeResult = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("upgradeResult: " + upgradeResult);
                        dataPackResult.setResultCode(upgradeResult);
                        //-add
                        dataPackResult.setResultName("终端升级结果");
                        dataPackTargetList.add(new DataPackTarget(dataPackResult));
                        break;
                    case 0x0200:
                        /* 位置信息汇报 */
                        // 1.位置基本信息
                        System.out.println("## 0x0200 - 位置信息汇报");
                        // 1.报警标志位
                        long alarmProps = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("alarmProps: " + alarmProps);
                        // 2.状态位
                        long statusProps = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("statusProps: " + statusProps);
                        // 3.位置数据
                        dataPackPosition = JTT808DataPackUtil.readPosition(buffer, dataPackObject, statusProps);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackPosition));

                        // 4.解析报警标志位
                        List<DataPackAlarm.Alarm> alarmList = JTT808DataPackUtil.detailAlarmProps(alarmProps);
                        if (null != alarmList && 0 < alarmList.size()) {
                            dataPackAlarm = new DataPackAlarm(dataPackObject);
                            dataPackAlarm.setPosition(dataPackPosition);
                            dataPackAlarm.setAlarmList(alarmList);
                            //--add
                            dataPackTargetList.add(new DataPackTarget(dataPackAlarm));
                        }

                        // 4.位置附加数据
                        dataPackTargetList.addAll(JTT808DataPackUtil.readPositionExtra(buffer, dataPackPosition, msgLength - 28));
                        break;
                    case 0x0201:
                        /* 位置信息查询应答 */
                        System.out.println("## 0x0201 - 位置信息查询应答");
                        // 1.应答流水号
                        responseMsgSeq = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("responseMsgSeq: " + responseMsgSeq);
                        // 2.报警标志位
                        alarmProps = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("alarmProps: " + alarmProps);
                        // 3.状态位
                        statusProps = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("statusProps: " + statusProps);
                        // 4.位置数据
                        dataPackPosition = JTT808DataPackUtil.readPosition(buffer, dataPackObject, statusProps);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackPosition));

                        // 5.解析报警标志位
                        alarmList = JTT808DataPackUtil.detailAlarmProps(alarmProps);
                        if (null != alarmList && 0 < alarmList.size()) {
                            dataPackAlarm = new DataPackAlarm(dataPackObject);
                            dataPackAlarm.setPosition(dataPackPosition);
                            dataPackAlarm.setAlarmList(alarmList);
                            //--add
                            dataPackTargetList.add(new DataPackTarget(dataPackAlarm));
                        }

                        // 6.位置附加数据
                        dataPackTargetList.addAll(JTT808DataPackUtil.readPositionExtra(buffer, dataPackPosition, msgLength - 28 - 2));
                    case 0x0301:
                        /* 事件报告 */
                        System.out.println("## 0x0301 - 事件报告");
                        //--结果数据
                        dataPackResult = new DataPackResult(dataPackObject);
                        // 1.事件 ID
                        int eventId = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("eventId: " + eventId);
                        dataPackResult.setResultCode(eventId);
                        //-add
                        dataPackResult.setResultName("事件报告结果");
                        dataPackTargetList.add(new DataPackTarget(dataPackResult));
                        break;
                    case 0x0302:
                        /* 提问应答 */
                        System.out.println("## 0x0302 - 提问应答");
                        //--结果数据
                        dataPackResult = new DataPackResult(dataPackObject);
                        // 1.应答流水号
                        responseMsgSeq = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("responseMsgSeq: " + responseMsgSeq);
                        dataPackResult.setPackId(responseMsgSeq);
                        // 2.答案 ID
                        int answerId = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("answerId: " + answerId);
                        dataPackResult.setResultCode(answerId);
                        //-add
                        dataPackResult.setResultName("提问结果");
                        dataPackTargetList.add(new DataPackTarget(dataPackResult));
                        break;
                    case 0x0303:
                        /* 信息点播/取消 */
                        System.out.println("## 0x0303 - 信息点播/取消");
                        //--结果数据
                        dataPackResult = new DataPackResult(dataPackObject);
                        // 1.信息类型：0：删除终端全部信息项；1：更新菜单；2：追加菜单；3：修改菜单
                        int messageType = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("messageType: " + messageType);
                        dataPackResult.setResultType(messageType);
                        // 2.点播/取消标志：0：取消；1：点播
                        int messageResult = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("messageResult: " + messageResult);
                        dataPackResult.setResultCode(messageResult);
                        //-add
                        dataPackResult.setResultName("信息点播/取消结果");
                        dataPackTargetList.add(new DataPackTarget(dataPackResult));
                        break;
                    case 0x0500:
                        /* 车辆控制应答 */
                        System.out.println("## 0x0500 - 车辆控制应答");
                        //--结果数据
                        dataPackResult = new DataPackResult(dataPackObject);
                        // 1.应答流水号
                        responseMsgSeq = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("responseMsgSeq: " + responseMsgSeq);
                        dataPackResult.setPackId(responseMsgSeq);
                        //-add
                        dataPackResult.setResultName("车辆控制结果");
                        dataPackTargetList.add(new DataPackTarget(dataPackResult));

                        //--位置数据
                        // 2.报警标志位
                        alarmProps = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("alarmProps: " + alarmProps);
                        // 3.状态位
                        statusProps = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("statusProps: " + statusProps);
                        // 4.位置数据
                        dataPackPosition = JTT808DataPackUtil.readPosition(buffer, dataPackObject, statusProps);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackPosition));

                        // 5.解析报警标志位
                        alarmList = JTT808DataPackUtil.detailAlarmProps(alarmProps);
                        if (null != alarmList && 0 < alarmList.size()) {
                            dataPackAlarm = new DataPackAlarm(dataPackObject);
                            dataPackAlarm.setPosition(dataPackPosition);
                            dataPackAlarm.setAlarmList(alarmList);
                            //--add
                            dataPackTargetList.add(new DataPackTarget(dataPackAlarm));
                        }

                        // 6.位置附加数据
                        dataPackTargetList.addAll(JTT808DataPackUtil.readPositionExtra(buffer, dataPackPosition, msgLength - 28 - 2));
                        break;
                    case 0x0700:
                        /* 行驶记录仪数据上传 */
                        System.out.println("## 0x0700 - 行驶记录仪数据上传");
                        /*** 关联GB/T 19056，暂时不予实现 ***/
                        break;
                    case 0x0701:
                        /* 电子运单上报 */
                        System.out.println("## 0x0701 - 电子运单上报");
                        /*** 关联GB/T 19056，暂时不予实现 ***/
                        break;
                    case 0x0702:
                        /* 驾驶员身份信息采集上报 */
                        System.out.println("## 0x0702 - 驾驶员身份信息采集上报");
                        //--驾驶员数据
                        DataPackDriver dataPackDriver = new DataPackDriver(dataPackObject);
                        // 1.状态
                        int driverICStatus = JTT808DataPackUtil.readByte(buffer);
                        switch (driverICStatus) {
                            case 0x01:
                                // 0x01：从业资格证 IC 卡插入（驾驶员上班）
                                JTT808DataPackUtil.debug("--从业资格证 IC 卡插入（驾驶员上班）");
                                break;
                            case 0x02:
                                // 0x02：从业资格证 IC 卡拔出（驾驶员下班）
                                JTT808DataPackUtil.debug("--从业资格证 IC 卡拔出（驾驶员下班）");
                                break;
                        }
                        dataPackDriver.setDriverICStatus(driverICStatus);
                        // 2.时间
                        Date driverICTime = JTT808DataPackUtil.readDate(buffer);
                        JTT808DataPackUtil.debug("driverICTime: " + driverICTime);
                        dataPackDriver.setDriverICTime(driverICTime);
                        // 3.IC 卡读取结果
                        int driverICResult = JTT808DataPackUtil.readByte(buffer);
                        switch (driverICResult) {
                            case 0x00:
                                // 0x00：IC 卡读卡成功
                                JTT808DataPackUtil.debug("--IC 卡读卡成功");
                                break;
                            case 0x01:
                                // 0x01：读卡失败，原因为卡片密钥认证未通过
                                JTT808DataPackUtil.debug("--读卡失败，原因为卡片密钥认证未通过");
                                break;
                            case 0x02:
                                // 0x02：读卡失败，原因为卡片已被锁定
                                JTT808DataPackUtil.debug("--读卡失败，原因为卡片已被锁定");
                                break;
                            case 0x03:
                                // 0x03：读卡失败，原因为卡片被拔出
                                JTT808DataPackUtil.debug("--读卡失败，原因为卡片被拔出");
                                break;
                            case 0x04:
                                // 0x04：读卡失败，原因为数据校验错误
                                JTT808DataPackUtil.debug("--读卡失败，原因为数据校验错误");
                                break;
                        }
                        dataPackDriver.setDriverICResult(driverICResult);
                        // 4.驾驶员姓名
                        int driverNameLength = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("driverNameLength: " + driverNameLength);
                        String driverName = JTT808DataPackUtil.readString(buffer, driverNameLength);
                        JTT808DataPackUtil.debug("driverName: " + driverName);
                        dataPackDriver.setDriverName(driverName);
                        // 5.从业资格证编码
                        String driverCertCode = JTT808DataPackUtil.readString(buffer, 20);
                        JTT808DataPackUtil.debug("driverCertCode: " + driverCertCode);
                        dataPackDriver.setDriverCertCode(driverCertCode);
                        // 6.发证机构名称
                        int driverCertOrganizationLength = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("driverCertOrganizationLength: " + driverCertOrganizationLength);
                        String driverCertOrganizationName = JTT808DataPackUtil.readString(buffer, driverCertOrganizationLength);
                        JTT808DataPackUtil.debug("driverCertOrganizationName: " + driverCertOrganizationName);
                        dataPackDriver.setDriverCertOrganizationName(driverCertOrganizationName);
                        // 7.证件有效期
                        Date driverCertExpireDate = JTT808DataPackUtil.readDateOnly(buffer);
                        JTT808DataPackUtil.debug("driverCertExpireDate: " + driverCertExpireDate);
                        dataPackDriver.setDriverCertExpireDate(driverCertExpireDate);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackDriver));
                        break;
                    case 0x0704:
                        /* 定位数据批量上传 */
                        System.out.println("## 0x0704 - 定位数据批量上传");
                        // 1.数据项个数
                        int positionTotal = JTT808DataPackUtil.readWord(buffer);
                        // 2.位置数据类型
                        int positionType = JTT808DataPackUtil.readByte(buffer);
                        switch (positionType) {
                            case 0x00:
                                // 0：正常位置批量汇报
                                JTT808DataPackUtil.debug("--正常位置批量汇报");
                                break;
                            case 0x01:
                                // 1：盲区补报
                                JTT808DataPackUtil.debug("--盲区补报");
                                break;
                        }
                        // 3.位置汇报数据项
                        if (0 < positionTotal) {
                            int positionLength;
                            for (int i = 0; i < positionTotal; i++) {
                                // 3.1 位置汇报数据体长度
                                positionLength = JTT808DataPackUtil.readWord(buffer);
                                JTT808DataPackUtil.debug("positionLength: " + positionLength);
                                // 3.2 报警标志位
                                alarmProps = JTT808DataPackUtil.readDWord(buffer);
                                JTT808DataPackUtil.debug("alarmProps: " + alarmProps);
                                // 3.3 状态位
                                statusProps = JTT808DataPackUtil.readDWord(buffer);
                                JTT808DataPackUtil.debug("statusProps: " + statusProps);
                                // 3.4 位置数据
                                dataPackPosition = JTT808DataPackUtil.readPosition(buffer, dataPackObject, statusProps);
                                //--add
                                dataPackTargetList.add(new DataPackTarget(dataPackPosition));

                                // 3.5 解析报警标志位
                                alarmList = JTT808DataPackUtil.detailAlarmProps(alarmProps);
                                if (null != alarmList && 0 < alarmList.size()) {
                                    dataPackAlarm = new DataPackAlarm(dataPackObject);
                                    dataPackAlarm.setPosition(dataPackPosition);
                                    dataPackAlarm.setAlarmList(alarmList);
                                    //--add
                                    dataPackTargetList.add(new DataPackTarget(dataPackAlarm));
                                }

                                // 3.6 位置附加数据
                                dataPackTargetList.addAll(JTT808DataPackUtil.readPositionExtra(buffer, dataPackPosition, positionLength - 28));
                            }
                        }
                        break;
                    case 0x0705:
                        /* CAN 总线数据上传-暂时不存 */
                        System.out.println("## 0x0705 - CAN 总线数据上传");
                        // 1.数据项个数
                        int canTotal = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("canTotal: " + canTotal);
                        // 2.CAN 总线数据接收时间
                        String canReceiveTime = JTT808DataPackUtil.readBCD(buffer, 5);
                        JTT808DataPackUtil.debug("canReceiveTime: " + canReceiveTime);
                        // 3.CAN 总线数据项
                        if (0 < canTotal) {
                            long canId;
                            int canChannel;
                            int canFrameType;
                            int canCollectMode;
                            byte[] canData;
                            for (int i = 0; i < canTotal; i++) {
                                // 3.1 CAN ID
                                canId = JTT808DataPackUtil.readDWord(buffer);
                                // 3.1.1 bit31 表示 CAN 通道号，0：CAN1，1：CAN2
                                canChannel = (byte) (canId >> 31) & 0x01;
                                JTT808DataPackUtil.debug("canChannel: " + canChannel);
                                switch (canChannel) {
                                    case 0x00:
                                        // 0：CAN1
                                        JTT808DataPackUtil.debug("--CAN1");
                                        break;
                                    case 0x01:
                                        // 1：CAN2
                                        JTT808DataPackUtil.debug("--CAN2");
                                        break;
                                }
                                // 3.1.2 bit30 表示帧类型，0：标准帧，1：扩展帧
                                canFrameType = (byte) (canId >> 30) & 0x01;
                                JTT808DataPackUtil.debug("canFrameType: " + canFrameType);
                                switch (canFrameType) {
                                    case 0x00:
                                        // 0：标准帧
                                        JTT808DataPackUtil.debug("--标准帧");
                                        break;
                                    case 0x01:
                                        // 1：扩展帧
                                        JTT808DataPackUtil.debug("--扩展帧");
                                        break;
                                }
                                // 3.1.3 bit29 表示数据采集方式，0：原始数据，1：采集区间的平均值
                                canCollectMode = (byte) (canId >> 29) & 0x01;
                                JTT808DataPackUtil.debug("canCollectMode: " + canCollectMode);
                                switch (canCollectMode) {
                                    case 0x00:
                                        // 0：原始数据
                                        JTT808DataPackUtil.debug("--原始数据");
                                        break;
                                    case 0x01:
                                        // 1：采集区间的平均值
                                        JTT808DataPackUtil.debug("--采集区间的平均值");
                                        break;
                                }
                                // 3.1.4 bit28-bit0 表示 CAN 总线 ID
                                canId = canId & 0x1FFFFFFF;
                                JTT808DataPackUtil.debug("canId: " + canId);
                                // 3.2 CAN DATA
                                canData = JTT808DataPackUtil.readBytes(buffer, 8);
                                JTT808DataPackUtil.debug("canData: " + DatatypeConverter.printHexBinary(canData));
                            }
                        }
                        break;
                    case 0x0800:
                        /* 多媒体事件信息上传 */
                        System.out.println("## 0x0800 - 多媒体事件信息上传");
                        //--多媒体数据
                        DataPackMedia dataPackMedia = new DataPackMedia(dataPackObject);
                        // 1.多媒体数据 ID
                        long mediaId = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("mediaId: " + mediaId);
                        dataPackMedia.setmId(mediaId);
                        // 2.多媒体类型：0：图像；1：音频；2：视频；
                        int mediaClassify = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("mediaClassify: " + mediaClassify);
                        switch (mediaClassify) {
                            case 0x00:
                                // 0：图像
                                JTT808DataPackUtil.debug("--图像");
                                break;
                            case 0x01:
                                // 1：音频
                                JTT808DataPackUtil.debug("--音频");
                                break;
                            case 0x02:
                                // 2：视频
                                JTT808DataPackUtil.debug("--视频");
                                break;
                        }
                        dataPackMedia.setType(mediaClassify);
                        // 3.多媒体格式编码：0：JPEG；1：TIF；2：MP3；3：WAV；4：WMV；
                        int mediaFormat = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("mediaFormat: " + mediaFormat);
                        switch (mediaFormat) {
                            case 0x00:
                                // 0：JPEG
                                JTT808DataPackUtil.debug("--JPEG");
                                break;
                            case 0x01:
                                // 1：TIF
                                JTT808DataPackUtil.debug("--TIF");
                                break;
                            case 0x02:
                                // 2：MP3
                                JTT808DataPackUtil.debug("--MP3");
                                break;
                            case 0x03:
                                // 3：WAV
                                JTT808DataPackUtil.debug("--WAV");
                                break;
                            case 0x04:
                                // 4：WMV
                                JTT808DataPackUtil.debug("--WMV");
                                break;
                        }
                        dataPackMedia.setFormat(mediaFormat);
                        // 4.事件项编码：0：平台下发指令；1：定时动作；2：抢劫报警触发；3：碰撞侧翻报警触发；4：门开拍照；
                        //             5：门关拍照；6：车门由开变关，时速从＜20公里到超过20公里；7：定距拍照；
                        int mediaEventCode = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("mediaEventCode: " + mediaEventCode);
                        switch (mediaEventCode) {
                            case 0x00:
                                // 0：平台下发指令
                                JTT808DataPackUtil.debug("--平台下发指令");
                                break;
                            case 0x01:
                                // 1：定时动作
                                JTT808DataPackUtil.debug("--定时动作");
                                break;
                            case 0x02:
                                // 2：抢劫报警触发
                                JTT808DataPackUtil.debug("--抢劫报警触发");
                                break;
                            case 0x03:
                                // 3：碰撞侧翻报警触发
                                JTT808DataPackUtil.debug("--碰撞侧翻报警触发");
                                break;
                            case 0x04:
                                // 4：门开拍照
                                JTT808DataPackUtil.debug("--门开拍照");
                                break;
                            case 0x05:
                                // 5：门关拍照
                                JTT808DataPackUtil.debug("--门关拍照");
                                break;
                            case 0x06:
                                // 6：车门由开变关，时速从＜20公里到超过20公里
                                JTT808DataPackUtil.debug("--车门由开变关，时速从＜20公里到超过20公里");
                                break;
                            case 0x07:
                                // 7：定距拍照
                                JTT808DataPackUtil.debug("--定距拍照");
                                break;
                        }
                        dataPackMedia.setEventCode(mediaEventCode);
                        // 5.通道 ID
                        int mediaChannelId = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("mediaChannelId: " + mediaChannelId);
                        dataPackMedia.setChannelId(mediaChannelId);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackMedia));
                        break;
                    case 0x0801:
                        /* 多媒体数据上传 */
                        System.out.println("## 0x0801 - 多媒体数据上传");
                        //--多媒体数据
                        dataPackMedia = new DataPackMedia(dataPackObject);
                        // 第1个子包
                        if (1 == msgSubPackIndex && 0 < msgSubPackTotal) {
                            // 1.多媒体数据 ID
                            mediaId = JTT808DataPackUtil.readDWord(buffer);
                            JTT808DataPackUtil.debug("mediaId: " + mediaId);
                            dataPackMedia.setmId(mediaId);
                            // 2.多媒体类型：0：图像；1：音频；2：视频；
                            mediaClassify = JTT808DataPackUtil.readByte(buffer);
                            JTT808DataPackUtil.debug("mediaClassify: " + mediaClassify);
                            switch (mediaClassify) {
                                case 0x00:
                                    // 0：图像
                                    JTT808DataPackUtil.debug("--图像");
                                    break;
                                case 0x01:
                                    // 1：音频
                                    JTT808DataPackUtil.debug("--音频");
                                    break;
                                case 0x02:
                                    // 2：视频
                                    JTT808DataPackUtil.debug("--视频");
                                    break;
                            }
                            dataPackMedia.setType(mediaClassify);
                            // 3.多媒体格式编码：0：JPEG；1：TIF；2：MP3；3：WAV；4：WMV；
                            mediaFormat = JTT808DataPackUtil.readByte(buffer);
                            JTT808DataPackUtil.debug("mediaFormat: " + mediaFormat);
                            switch (mediaFormat) {
                                case 0x00:
                                    // 0：JPEG
                                    JTT808DataPackUtil.debug("--JPEG");
                                    break;
                                case 0x01:
                                    // 1：TIF
                                    JTT808DataPackUtil.debug("--TIF");
                                    break;
                                case 0x02:
                                    // 2：MP3
                                    JTT808DataPackUtil.debug("--MP3");
                                    break;
                                case 0x03:
                                    // 3：WAV
                                    JTT808DataPackUtil.debug("--WAV");
                                    break;
                                case 0x04:
                                    // 4：WMV
                                    JTT808DataPackUtil.debug("--WMV");
                                    break;
                            }
                            dataPackMedia.setFormat(mediaFormat);
                            // 4.事件项编码：0：平台下发指令；1：定时动作；2：抢劫报警触发；3：碰撞侧翻报警触发；
                            mediaEventCode = JTT808DataPackUtil.readByte(buffer);
                            JTT808DataPackUtil.debug("mediaEventCode: " + mediaEventCode);
                            switch (mediaEventCode) {
                                case 0x00:
                                    // 0：平台下发指令
                                    JTT808DataPackUtil.debug("--平台下发指令");
                                    break;
                                case 0x01:
                                    // 1：定时动作
                                    JTT808DataPackUtil.debug("--定时动作");
                                    break;
                                case 0x02:
                                    // 2：抢劫报警触发
                                    JTT808DataPackUtil.debug("--抢劫报警触发");
                                    break;
                                case 0x03:
                                    // 3：碰撞侧翻报警触发
                                    JTT808DataPackUtil.debug("--碰撞侧翻报警触发");
                                    break;
                            }
                            dataPackMedia.setEventCode(mediaEventCode);
                            // 5.通道 ID
                            mediaChannelId = JTT808DataPackUtil.readByte(buffer);
                            JTT808DataPackUtil.debug("mediaChannelId: " + mediaChannelId);
                            dataPackMedia.setChannelId(mediaChannelId);
                            // 6.位置信息汇报(0x0200)消息体
                            // 6.1 报警标志位
                            alarmProps = JTT808DataPackUtil.readDWord(buffer);
                            JTT808DataPackUtil.debug("alarmProps: " + alarmProps);
                            // 6.2 状态位
                            statusProps = JTT808DataPackUtil.readDWord(buffer);
                            JTT808DataPackUtil.debug("statusProps: " + statusProps);
                            // 6.3 位置数据
                            dataPackPosition = JTT808DataPackUtil.readPosition(buffer, dataPackObject, statusProps);
                            //--add
                            dataPackTargetList.add(new DataPackTarget(dataPackPosition));
                            //--多媒体位置
                            dataPackMedia.setPosition(dataPackPosition);

                            // 6.4 解析报警标志位
                            alarmList = JTT808DataPackUtil.detailAlarmProps(alarmProps);
                            if (null != alarmList && 0 < alarmList.size()) {
                                dataPackAlarm = new DataPackAlarm(dataPackObject);
                                dataPackAlarm.setPosition(dataPackPosition);
                                dataPackAlarm.setAlarmList(alarmList);
                                //--add
                                dataPackTargetList.add(new DataPackTarget(dataPackAlarm));
                            }
                        }

                        // 7.多媒体数据包
                        byte[] mediaBytes = JTT808DataPackUtil.readBytes(buffer, buffer.readableBytes() - 2);
                        System.out.println("mediaBytes: " + mediaBytes);
                        dataPackMedia.setData(Base64.getEncoder().encodeToString(mediaBytes));
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackMedia));
                        break;
                    case 0x0805:
                        /* 摄像头立即拍摄命令应答 */
                        System.out.println("## 0x0805 - 摄像头立即拍摄命令应答");
                        //--结果数据
                        dataPackResult = new DataPackResult(dataPackObject);
                        // 1.应答流水号
                        responseMsgSeq = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("responseMsgSeq: " + responseMsgSeq);
                        dataPackResult.setPackId(responseMsgSeq);
                        // 2.结果：0：成功；1：失败；2：通道不支持
                        int mediaResult = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("mediaResult: " + mediaResult);
                        switch (mediaResult) {
                            case 0x00:
                                // 0：成功
                                JTT808DataPackUtil.debug("--成功");
                                break;
                            case 0x01:
                                // 1：失败
                                JTT808DataPackUtil.debug("--失败");
                                break;
                            case 0x02:
                                // 2：通道不支持
                                JTT808DataPackUtil.debug("--通道不支持");
                                break;
                        }
                        dataPackResult.setResultCode(mediaResult);
                        // 3.多媒体 ID 个数
                        int mediaTotal = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("mediaTotal: " + mediaTotal);
                        // 4.多媒体 ID 列表
                        List<Integer> itemIdList = new ArrayList<>();
                        if (0 < mediaTotal) {
                            int itemId;
                            for (int i = 0; i < mediaTotal; i++) {
                                // JTT808DataPackUtil.readBytes(buffer, 4)
                                itemId = new Long(JTT808DataPackUtil.readDWord(buffer)).intValue();
                                JTT808DataPackUtil.debug("--" + itemId);
                                itemIdList.add(itemId);
                            }
                        }
                        dataPackResult.setReusltItems(itemIdList);
                        //--add
                        dataPackResult.setResultName("摄像头立即拍摄命令结果");
                        dataPackTargetList.add(new DataPackTarget(dataPackResult));
                        break;
                    case 0x0802:
                        /* 存储多媒体数据检索应答 */
                        System.out.println("## 0x0802 - 存储多媒体数据检索应答");
                        //--结果数据
                        dataPackResult = new DataPackResult(dataPackObject);
                        // 1.应答流水号
                        responseMsgSeq = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("responseMsgSeq: " + responseMsgSeq);
                        dataPackResult.setPackId(responseMsgSeq);
                        // 2.多媒体数据总项数
                        mediaTotal = JTT808DataPackUtil.readWord(buffer);
                        JTT808DataPackUtil.debug("mediaTotal: " + mediaTotal);
                        dataPackResult.setResultCode(mediaTotal);
                        //--add
                        dataPackResult.setResultName("存储多媒体数据检索结果");
                        dataPackTargetList.add(new DataPackTarget(dataPackResult));

                        // 3.检索项
                        if (0 < mediaTotal) {
                            for (int i = 0; i < mediaTotal; i++) {
                                //--多媒体数据
                                dataPackMedia = new DataPackMedia(dataPackObject);
                                // 3.1 多媒体数据 ID
                                mediaId = JTT808DataPackUtil.readDWord(buffer);
                                JTT808DataPackUtil.debug("mediaId: " + mediaId);
                                dataPackMedia.setmId(mediaId);
                                // 3.2 多媒体类型：0：图像；1：音频；2：视频；
                                mediaClassify = JTT808DataPackUtil.readByte(buffer);
                                JTT808DataPackUtil.debug("mediaClassify: " + mediaClassify);
                                switch (mediaClassify) {
                                    case 0x00:
                                        // 0：图像
                                        JTT808DataPackUtil.debug("--图像");
                                        break;
                                    case 0x01:
                                        // 1：音频
                                        JTT808DataPackUtil.debug("--音频");
                                        break;
                                    case 0x02:
                                        // 2：视频
                                        JTT808DataPackUtil.debug("--视频");
                                        break;
                                }
                                dataPackMedia.setType(mediaClassify);
                                // 3.3 通道 ID
                                mediaChannelId = JTT808DataPackUtil.readByte(buffer);
                                JTT808DataPackUtil.debug("mediaChannelId: " + mediaChannelId);
                                dataPackMedia.setChannelId(mediaChannelId);
                                // 3.4 事件项编码：0：平台下发指令；1：定时动作；2：抢劫报警触发；3：碰撞侧翻报警触发；
                                mediaEventCode = JTT808DataPackUtil.readByte(buffer);
                                JTT808DataPackUtil.debug("mediaEventCode: " + mediaEventCode);
                                switch (mediaEventCode) {
                                    case 0x00:
                                        // 0：平台下发指令
                                        JTT808DataPackUtil.debug("--平台下发指令");
                                        break;
                                    case 0x01:
                                        // 1：定时动作
                                        JTT808DataPackUtil.debug("--定时动作");
                                        break;
                                    case 0x02:
                                        // 2：抢劫报警触发
                                        JTT808DataPackUtil.debug("--抢劫报警触发");
                                        break;
                                    case 0x03:
                                        // 3：碰撞侧翻报警触发
                                        JTT808DataPackUtil.debug("--碰撞侧翻报警触发");
                                        break;
                                }
                                dataPackMedia.setEventCode(mediaEventCode);
                                // 3.5 位置信息汇报(0x0200)消息体-BYTE[28]
                                // 表示拍摄或录制的起始时刻的位置基本信息数据
                                // 3.5.1 报警标志位
                                alarmProps = JTT808DataPackUtil.readDWord(buffer);
                                JTT808DataPackUtil.debug("alarmProps: " + alarmProps);
                                // 3.5.2 状态位
                                statusProps = JTT808DataPackUtil.readDWord(buffer);
                                JTT808DataPackUtil.debug("statusProps: " + statusProps);
                                // 3.5.3 位置数据
                                dataPackPosition = JTT808DataPackUtil.readPosition(buffer, dataPackObject, statusProps);
                                //--add
                                dataPackTargetList.add(new DataPackTarget(dataPackPosition));
                                //--多媒体位置
                                dataPackMedia.setPosition(dataPackPosition);
                                dataPackTargetList.add(new DataPackTarget(dataPackMedia));

                                // 3.5.4 解析报警标志位
                                alarmList = JTT808DataPackUtil.detailAlarmProps(alarmProps);
                                if (null != alarmList && 0 < alarmList.size()) {
                                    dataPackAlarm = new DataPackAlarm(dataPackObject);
                                    dataPackAlarm.setPosition(dataPackPosition);
                                    dataPackAlarm.setAlarmList(alarmList);
                                    //--add
                                    dataPackTargetList.add(new DataPackTarget(dataPackAlarm));
                                }
                            }
                        }
                        break;
                    case 0x0900:
                        /* 数据上行透传-//暂时无用 */
                        System.out.println("## 0x0900 - 数据上行透传");
                        // 1.透传消息类型
                        int transMsgType = JTT808DataPackUtil.readByte(buffer);
                        JTT808DataPackUtil.debug("transMsgType: " + transMsgType);
                        switch (transMsgType) {
                            case 0x00:
                                // 0x00 - GNSS 模块详细定位数据
                                JTT808DataPackUtil.debug("--");
                                break;
                            case 0x0B:
                                // 0x0B - 道路运输证 IC 卡信息
                                JTT808DataPackUtil.debug("--");
                                break;
                            case 0x41:
                                // 0x41 - 串口 1 透传
                                JTT808DataPackUtil.debug("--");
                                break;
                            case 0x42:
                                // 0x42 - 串口 2 透传
                                JTT808DataPackUtil.debug("--");
                                break;
                            default:
                                // 0xF0-0xFF - 用户自定义透传消息
                        }
                        // 2.透传消息内容
                        // 未定义数据类型
                        break;
                    case 0x0901:
                        /* 数据压缩上报-//暂时无用 */
                        System.out.println("## 0x0901 - 数据压缩上报");
                        // 1.压缩消息长度
                        long gzipMsgLength = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("gzipMsgLength: " + gzipMsgLength);
                        // 2.压缩消息体
                        // 未定义数据类型
                        break;
                    case 0x0A00:
                        /* 终端 RSA 公钥 */
                        System.out.println("## 0x0A00 - 终端 RSA 公钥");
                        //--RSA数据
                        DataPackRsa dataPackRsa = new DataPackRsa(dataPackObject);
                        // 1.终端 RSA 公钥{e,n}中的 e
                        long rsaE = JTT808DataPackUtil.readDWord(buffer);
                        JTT808DataPackUtil.debug("rsaE: " + rsaE);
                        dataPackRsa.setE(rsaE);
                        // 2.RSA 公钥{e,n}中的 n
                        byte[] rsaN = JTT808DataPackUtil.readBytes(buffer, 128);
                        JTT808DataPackUtil.debug("rsaN: " + rsaN);
                        dataPackRsa.setN(rsaN);
                        //--add
                        dataPackTargetList.add(new DataPackTarget(dataPackRsa));
                        break;
                    default:
                        /**
                         * 0x8F00~0x8FFF 平台下行消息保留
                         * 0x0F00~0x0FFF 终端上行消息保留
                         */
                        System.out.println("## msgId(" + msgId + ") can't be parsed.");
                }


            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // 释放ByteBuf
                ReferenceCountUtil.release(buffer);
            }
        }
        return dataPackTargetList;
    }

    @Override
    public Map<String, Object> getMetaData(ByteBuf buffer) {
        byte[] dataPackBytes = validate(JTT808DataPackUtil.readBytes(buffer, buffer.readableBytes()));
        if (null != dataPackBytes) {
            Map<String, Object> metaDataMap = new HashMap<>();
            // 协议版本
            metaDataMap.put("protocol", PROTOCOL_PREFIX + PROTOCOL_VERSION);

            // 设备ID
            int number;
            StringBuffer stringBuffer = new StringBuffer();
            for (int i = 5; i < 11; i++) {
                number = dataPackBytes[i];
                stringBuffer.append(number >> 4 & 0x0F);
                stringBuffer.append(number & 0x0F);
            }
            metaDataMap.put("deviceId", stringBuffer.toString());

            // VIN
            // 无法给出
            return metaDataMap;
        }
        return null;
    }
}
