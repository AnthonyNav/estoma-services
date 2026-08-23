FROM python:3.13-alpine
WORKDIR /runner
COPY requirements.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py /runner/
ENTRYPOINT ["python", "/runner/final_patch.py"]
