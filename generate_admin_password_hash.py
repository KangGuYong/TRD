"""admin_accounts.password_hash 에 넣을 BCrypt 해시를 생성한다.

Spring Security의 BCryptPasswordEncoder()는 기본 strength(rounds) 10을 쓰므로
동일하게 rounds=10으로 맞춘다.

사용법:
    pip install bcrypt
    python generate_admin_password_hash.py
    (비밀번호는 화면에 표시되지 않는 프롬프트로 입력받는다)

출력된 해시를 아래 SQL의 값으로 사용:
    UPDATE admin_accounts
    SET password_hash = '<여기에 출력된 해시>'
    WHERE login_id = '해당관리자아이디';
"""

import getpass

import bcrypt


def main() -> None:
    password = getpass.getpass("새 비밀번호: ")
    confirm = getpass.getpass("새 비밀번호 확인: ")

    if password != confirm:
        print("비밀번호가 일치하지 않습니다.")
        return

    hashed = bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=10))
    print(hashed.decode("utf-8"))


if __name__ == "__main__":
    main()
