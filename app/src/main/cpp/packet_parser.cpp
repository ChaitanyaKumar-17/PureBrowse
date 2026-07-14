#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <vector>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>

#define LOG_TAG "VPN-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::string extract_dns_query(const uint8_t* payload, size_t payload_len) {
    if (payload_len <= 12) return "";
    size_t offset = 12; // Skip DNS header
    std::string domain = "";
    while (offset < payload_len) {
        uint8_t len = payload[offset];
        if (len == 0) break; // End of name
        // Check for pointer (compression), rare in query section
        if ((len & 0xC0) == 0xC0) break; 
        
        offset++;
        if (offset + len > payload_len) return "";
        
        if (!domain.empty()) domain += ".";
        domain.append((const char*)(payload + offset), len);
        offset += len;
    }
    return domain;
}

extern "C" JNIEXPORT void JNICALL
Java_com_purebrowse_vpn_PureBrowseVpnService_startPacketProcessing(
        JNIEnv* env,
        jobject thisObj,
        jint tun_fd) {
    
    LOGI("Packet processing loop started. TUN FD: %d", tun_fd);
    
    std::vector<uint8_t> buffer(65535);

    while (true) {
        ssize_t length = read(tun_fd, buffer.data(), buffer.size());
        
        if (length < 0) {
            LOGE("Error reading from TUN interface. Loop exiting.");
            break;
        }

        if (length == 0) continue;

        uint8_t version = buffer[0] >> 4;
        if (version != 4) {
            write(tun_fd, buffer.data(), length);
            continue;
        }

        uint8_t ihl = buffer[0] & 0x0F;
        uint32_t ip_header_len = ihl * 4;

        if (length < ip_header_len) continue;

        uint8_t protocol = buffer[9];

        // UDP = 17. Check for DNS on port 53.
        if (protocol == 17) {
            if (length >= ip_header_len + 8) { 
                uint16_t dest_port = (buffer[ip_header_len + 2] << 8) | buffer[ip_header_len + 3];

                if (dest_port == 53) {
                    std::string domain = extract_dns_query(buffer.data() + ip_header_len + 8, length - ip_header_len - 8);
                    
                    if (!domain.empty()) {
                        jstring jdomain = env->NewStringUTF(domain.c_str());
                        jclass serviceClass = env->GetObjectClass(thisObj);
                        jmethodID isBlockedMethod = env->GetMethodID(serviceClass, "isDomainBlocked", "(Ljava/lang/String;)Z");
                        jboolean isBlocked = env->CallBooleanMethod(thisObj, isBlockedMethod, jdomain);
                        env->DeleteLocalRef(jdomain);
                        
                        if (isBlocked) {
                            LOGI("BLOCKED: %s", domain.c_str());
                            // Simply drop the packet by NOT writing it back to the TUN interface.
                            // A real implementation would craft a DNS NXDOMAIN response packet and inject it here for faster failure.
                            continue;
                        } else {
                            LOGI("ALLOWED: %s", domain.c_str());
                        }
                    }
                }
            }
        }

        // Pass allowed or non-DNS packets through untouched
        write(tun_fd, buffer.data(), length);
    }
}
