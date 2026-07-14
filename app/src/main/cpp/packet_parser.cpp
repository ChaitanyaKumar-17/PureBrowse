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

extern "C" JNIEXPORT void JNICALL
Java_com_purebrowse_vpn_PureBrowseVpnService_startPacketProcessing(
        JNIEnv* env,
        jobject /* this */,
        jint tun_fd) {
    
    LOGI("Packet processing loop started. TUN FD: %d", tun_fd);
    
    std::vector<uint8_t> buffer(65535);

    while (true) {
        ssize_t length = read(tun_fd, buffer.data(), buffer.size());
        
        if (length < 0) {
            LOGE("Error reading from TUN interface. Loop exiting.");
            break;
        }

        if (length == 0) {
            continue;
        }

        // Basic IPv4 Check
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
                    LOGI("Intercepted DNS query packet! Length: %zd", length);
                    
                    // TODO: JNI callback to Kotlin DomainDao here
                    // TODO: Inject NXDOMAIN if blocked

                    // For MVP, we pass it through
                    write(tun_fd, buffer.data(), length);
                    continue;
                }
            }
        }

        // Pass non-DNS packets through untouched
        write(tun_fd, buffer.data(), length);
    }
}
