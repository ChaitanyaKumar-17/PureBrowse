#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <vector>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <cstring>

#define LOG_TAG "VPN-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

std::string extract_dns_query(const uint8_t* payload, size_t payload_len) {
    if (payload_len <= 12) return "";
    size_t offset = 12;
    std::string domain = "";
    while (offset < payload_len) {
        uint8_t len = payload[offset];
        if (len == 0) break;
        if ((len & 0xC0) == 0xC0) break; 
        offset++;
        if (offset + len > payload_len) return "";
        if (!domain.empty()) domain += ".";
        domain.append((const char*)(payload + offset), len);
        offset += len;
    }
    return domain;
}

uint16_t checksum(uint16_t *buf, int nwords) {
    uint32_t sum = 0;
    for (sum = 0; nwords > 0; nwords--) sum += *buf++;
    sum = (sum >> 16) + (sum & 0xffff);
    sum += (sum >> 16);
    return (uint16_t)(~sum);
}

void send_dns_response(int tun_fd, uint8_t* packet, size_t length, bool nxdomain, const uint8_t* real_response = nullptr, size_t real_resp_len = 0) {
    uint8_t ihl = packet[0] & 0x0F;
    uint32_t ip_len = ihl * 4;
    
    // Swap IPs
    uint32_t src_ip, dst_ip;
    memcpy(&src_ip, packet + 12, 4);
    memcpy(&dst_ip, packet + 16, 4);
    memcpy(packet + 12, &dst_ip, 4);
    memcpy(packet + 16, &src_ip, 4);

    // Swap Ports
    uint16_t src_port, dst_port;
    memcpy(&src_port, packet + ip_len, 2);
    memcpy(&dst_port, packet + ip_len + 2, 2);
    memcpy(packet + ip_len, &dst_port, 2);
    memcpy(packet + ip_len + 2, &src_port, 2);

    if (nxdomain) {
        packet[ip_len + 8 + 2] = 0x81;
        packet[ip_len + 8 + 3] = 0x83; // NXDOMAIN
        packet[ip_len + 4] = 0; packet[ip_len + 5] = length - ip_len;
        packet[ip_len + 6] = 0; packet[ip_len + 7] = 0;
        write(tun_fd, packet, length);
    } else if (real_response) {
        std::vector<uint8_t> out(ip_len + 8 + real_resp_len);
        memcpy(out.data(), packet, ip_len + 8);
        memcpy(out.data() + ip_len + 8, real_response, real_resp_len);
        
        uint16_t total_len = htons(out.size());
        memcpy(out.data() + 2, &total_len, 2);
        
        uint16_t udp_len = htons(8 + real_resp_len);
        memcpy(out.data() + ip_len + 4, &udp_len, 2);
        out[ip_len + 6] = 0; out[ip_len + 7] = 0;
        
        out[10] = 0; out[11] = 0;
        uint16_t ip_csum = checksum((uint16_t*)out.data(), ip_len / 2);
        memcpy(out.data() + 10, &ip_csum, 2);

        write(tun_fd, out.data(), out.size());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_purebrowse_vpn_PureBrowseVpnService_startPacketProcessing(
        JNIEnv* env, jobject thisObj, jint tun_fd) {
    
    int proxy_fd = socket(AF_INET, SOCK_DGRAM, 0);
    jclass serviceClass = env->GetObjectClass(thisObj);
    jmethodID protectMethod = env->GetMethodID(serviceClass, "protectSocket", "(I)Z");
    env->CallBooleanMethod(thisObj, protectMethod, proxy_fd);
    
    struct sockaddr_in dns_server;
    dns_server.sin_family = AF_INET;
    dns_server.sin_port = htons(53);
    inet_pton(AF_INET, "8.8.8.8", &dns_server.sin_addr);

    struct timeval tv;
    tv.tv_sec = 2; tv.tv_usec = 0;
    setsockopt(proxy_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    std::vector<uint8_t> buffer(65535);
    std::vector<uint8_t> resp_buffer(65535);

    while (true) {
        ssize_t length = read(tun_fd, buffer.data(), buffer.size());
        if (length <= 0) break;

        uint8_t version = buffer[0] >> 4;
        if (version != 4) continue;

        uint8_t ihl = buffer[0] & 0x0F;
        uint32_t ip_header_len = ihl * 4;
        if (length < ip_header_len) continue;

        uint8_t protocol = buffer[9];
        if (protocol == 17 && length >= ip_header_len + 8) { 
            uint16_t dest_port = (buffer[ip_header_len + 2] << 8) | buffer[ip_header_len + 3];

            if (dest_port == 53) {
                std::string domain = extract_dns_query(buffer.data() + ip_header_len + 8, length - ip_header_len - 8);
                if (!domain.empty()) {
                    jstring jdomain = env->NewStringUTF(domain.c_str());
                    jmethodID isBlockedMethod = env->GetMethodID(serviceClass, "isDomainBlocked", "(Ljava/lang/String;)Z");
                    jboolean isBlocked = env->CallBooleanMethod(thisObj, isBlockedMethod, jdomain);
                    env->DeleteLocalRef(jdomain);
                    
                    if (isBlocked) {
                        send_dns_response(tun_fd, buffer.data(), length, true);
                        continue;
                    } else {
                        sendto(proxy_fd, buffer.data() + ip_header_len + 8, length - ip_header_len - 8, 0, (struct sockaddr*)&dns_server, sizeof(dns_server));
                        socklen_t addr_len = sizeof(dns_server);
                        ssize_t resp_len = recvfrom(proxy_fd, resp_buffer.data(), resp_buffer.size(), 0, (struct sockaddr*)&dns_server, &addr_len);
                        if (resp_len > 0) {
                            send_dns_response(tun_fd, buffer.data(), length, false, resp_buffer.data(), resp_len);
                        }
                        continue;
                    }
                }
            }
        }
    }
    close(proxy_fd);
}
